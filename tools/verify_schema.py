"""Verify the full schema with contract fixtures; always roll back all test writes.

Run against an empty dev/test database. --trial creates all migrations within the
same rolled-back transaction. --pending-migrations tries unapplied migrations on
an installed empty dev schema. Neither mode changes Flyway history or commits DDL.
Credentials are read from Java-properties .env and environment, never printed.
"""
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
from urllib.parse import parse_qs, urlsplit
from uuid import uuid4

import javaproperties
import psycopg2
from psycopg2.extras import Json
import yaml

ROOT = Path(__file__).resolve().parents[2]
MIGRATIONS = ROOT / 'backend/src/main/resources/db/migration'


def connect(env_file):
    with env_file.open(encoding='utf-8-sig') as stream:
        settings = javaproperties.load(stream)
    settings.update(os.environ)
    url = urlsplit(settings.get('DB_MIGRATION_URL') or settings['DB_URL'])
    if url.scheme == 'jdbc':
        url = urlsplit(url.geturl()[5:])
    if url.scheme != 'postgresql' or not url.hostname or '-pooler' in url.hostname:
        raise ValueError('Verification requires a direct PostgreSQL JDBC URL')
    query = parse_qs(url.query)
    return psycopg2.connect(host=url.hostname, port=url.port or 5432,
                           dbname=url.path.lstrip('/'), user=settings['DB_USERNAME'],
                           password=settings['DB_PASSWORD'], connect_timeout=30,
                           sslmode=query.get('sslmode', ['require'])[0],
                           channel_binding=query.get('channelBinding', ['require'])[0])


def verify(conn, trial, pending_migrations=False):
    checks = []
    contract = yaml.safe_load((ROOT / 'api-contract/openapi.mobile.yaml').read_text(encoding='utf-8'))
    migration = (MIGRATIONS / 'V2__create_mobile_schema.sql').read_text(encoding='utf-8')
    with conn.cursor() as cur:
        def check(name, condition):
            if not condition:
                raise AssertionError(name)
            checks.append(name)

        def scalar(sql, params=None):
            cur.execute(sql, params)
            return cur.fetchone()[0]

        def rejects(name, sql, params, state='23514'):
            cur.execute('SAVEPOINT expected_failure')
            try:
                cur.execute(sql, params)
            except psycopg2.Error as error:
                code = error.pgcode
                cur.execute('ROLLBACK TO SAVEPOINT expected_failure')
                check(name, code == state)
            else:
                cur.execute('ROLLBACK TO SAVEPOINT expected_failure')
                raise AssertionError(name + ': invalid write was accepted')
            finally:
                cur.execute('RELEASE SAVEPOINT expected_failure')

        version = scalar('show server_version')
        tested_migrations = []
        if trial:
            check('trial target has no application tables', scalar(
                "select count(*) from information_schema.tables where table_schema='asl'") == 0)
            for file in sorted(MIGRATIONS.glob('V*__*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
                cur.execute(file.read_text(encoding='utf-8'))
                tested_migrations.append(file.name)
        elif pending_migrations:
            cur.execute('select version from public.flyway_schema_history where success and version is not null')
            installed = {row[0] for row in cur.fetchall()}
            for file in sorted(MIGRATIONS.glob('V*__*.sql'), key=lambda p: int(p.name.split('__')[0][1:])):
                if file.name.split('__')[0][1:] not in installed:
                    cur.execute(file.read_text(encoding='utf-8'))
                    tested_migrations.append(file.name)

        expected_tables = sorted(re.findall(r'^CREATE TABLE (\w+)', migration, re.M))
        cur.execute("select table_name from information_schema.tables where table_schema='asl' and table_type='BASE TABLE' order by 1")
        check('all 33 application tables exist in asl', [r[0] for r in cur.fetchall()] == expected_tables and len(expected_tables) == 33)
        expected_views = sorted(re.findall(r'^CREATE VIEW (\w+)', migration, re.M))
        cur.execute("select table_name from information_schema.views where table_schema='asl' order by 1")
        check('all 6 derived views exist', [r[0] for r in cur.fetchall()] == expected_views and len(expected_views) == 6)
        check('17 functions pin their runtime search_path', scalar(
            "select count(*) from pg_proc p join pg_namespace n on n.oid=p.pronamespace where n.nspname='asl' and 'search_path=asl, pg_temp'=any(p.proconfig)") == 17)
        trigger_count = scalar("select count(*) from pg_trigger t join pg_class c on c.oid=t.tgrelid join pg_namespace n on n.oid=c.relnamespace where n.nspname='asl' and not t.tgisinternal")
        check('pg_trgm is available', scalar("select count(*) from pg_extension where extname='pg_trgm'") == 1)
        check('fixture target is empty', scalar('select count(*) from asl.lessons') == 0 and scalar('select count(*) from asl.app_users') == 0)

        # Match scalar enums against the actual installed CHECK constraints.
        for table, column, schema, prop in [
            ('media_assets', 'mime_type', 'Media', 'mimeType'),
            ('lesson_versions', 'lesson_type', 'LessonSummary', 'type'),
            ('lesson_attempts', 'status', 'LessonAttempt', 'status'),
            ('favorites', 'target_type', 'Favorite', 'targetType'),
            ('user_reports', 'target_type', 'ReportRequest', 'targetType'),
            ('user_reports', 'reason', 'ReportRequest', 'reason'),
            ('store_products', 'store', 'StoreProduct', 'store'),
            ('store_products', 'period', 'StoreProduct', 'period'),
            ('subscriptions', 'status', 'Subscription', 'status')]:
            cur.execute("select pg_get_constraintdef(oid) from pg_constraint where conrelid=%s::regclass and contype='c'", ('asl.' + table,))
            definitions = [r[0] for r in cur.fetchall() if column in r[0]]
            expected = set(contract['components']['schemas'][schema]['properties'][prop]['enum'])
            check('enum ' + table + '.' + column, any(set(re.findall(r"'([^']+)'::text", d)) == expected for d in definitions))

        # Crucial: callers need not have asl in search_path for triggers to work.
        cur.execute('SET LOCAL search_path = public')
        user = str(uuid4())
        cur.execute("insert into asl.app_users(id,email,time_zone) values (%s,%s,'Asia/Ho_Chi_Minh')", (user, user+'@example.invalid'))
        rejects('invalid timezone rejected', 'update asl.app_users set time_zone=%s where id=%s', ('Invalid/Zone', user))
        unit = str(uuid4())
        cur.execute("insert into asl.units(id,title,position) values (%s,'Rollback fixture',0)", (unit,))
        lesson = contract['paths']['/api/v1/lessons/{lessonId}']['get']['responses']['200']['content']['application/json']['examples']['default']['value']
        cur.execute('insert into asl.lessons(id,unit_id,position) values (%s,%s,0)', (lesson['lessonId'], unit))
        cur.execute("insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (%s,%s,1,%s,%s)",
                    (lesson['versionId'], lesson['lessonId'], lesson['title'], lesson['type']))
        media = {}
        for sign in lesson['signs']:
            media[sign['video']['assetId']] = sign['video']
        for exercise in lesson['exercises']:
            for asset in exercise['media'].values():
                media[asset['assetId']] = asset
        for asset in media.values():
            cur.execute("insert into asl.media_assets(id,storage_key,mime_type,duration_ms,sha256_checksum,rights_reference,published_at) values (%s,%s,%s,%s,%s,'test-only rollback fixture',now())",
                        (asset['assetId'], 'test/'+asset['assetId'], asset['mimeType'], asset['durationMs'], '0'*64))
        for i, sign in enumerate(lesson['signs']):
            cur.execute("insert into asl.signs(id,name,content_kind,video_asset_id,published_at) values (%s,%s,'WORD',%s,now())", (sign['id'],sign['name'],sign['video']['assetId']))
            cur.execute("insert into asl.lesson_version_signs values (%s,%s,%s,'WORD',%s,%s)", (lesson['versionId'],sign['id'],sign['name'],sign['video']['assetId'],i))
        for exercise in lesson['exercises']:
            cur.execute('insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,prompt,payload,answer_key,points_per_correct) values (%s,%s,%s,%s,%s,%s,%s,%s)',
                        (exercise['exerciseId'],lesson['versionId'],exercise['position'],exercise['type'],exercise['prompt'],Json(exercise['payload']),Json(exercise['answerKey']) if exercise['answerKey'] is not None else None,exercise['pointsPerCorrect']))
            for sid in exercise['signIds']:
                cur.execute('insert into asl.exercise_signs values (%s,%s,%s)', (lesson['versionId'],exercise['exerciseId'],sid))
            for slot, asset in exercise['media'].items():
                cur.execute('insert into asl.lesson_exercise_media values (%s,%s,%s,%s)', (lesson['versionId'],exercise['exerciseId'],slot,asset['assetId']))
        cur.execute('update asl.lesson_versions set published_at=now(),is_current=true where id=%s', (lesson['versionId'],))
        check('all 13 contract exercise types load and publish', scalar('select count(distinct exercise_type) from asl.lesson_exercises') == 13)
        rejects('published content immutable', "update asl.lesson_exercises set prompt='changed' where id=%s", (lesson['exercises'][0]['exerciseId'],))
        rejects('published media immutable', "update asl.media_assets set storage_key='changed' where id=%s", (next(iter(media)),))
        rejects('self prerequisite rejected', 'insert into asl.lesson_prerequisites values (%s,%s)', (lesson['lessonId'],lesson['lessonId']))

        examples = contract['paths']['/api/v1/sync/mutations']['post']['requestBody']['content']['application/json']['examples']
        attempt = examples['default']['value']['mutations'][0]['data']
        def insert_attempt(data, attempt_id=None):
            cur.execute('insert into asl.lesson_attempts(user_id,attempt_id,lesson_id,lesson_version_id,status,started_at,client_updated_at,completed_at,completion_time_zone,checkpoint,max_points,earned_points,revision) values (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,100,%s,1)',
                        (user,attempt_id or data['attemptId'],data['lessonId'],data['versionId'],data['status'],data['startedAt'],data['updatedAt'],data['completedAt'],data['completionTimeZone'],Json(data['checkpoint']),data['earnedPoints']))
        insert_attempt(attempt)
        check('in-progress attempt awards no points', scalar('select total_points from asl.user_total_points where user_id=%s',(user,)) == 0)
        completed = examples['completed_after_review']['value']['mutations'][0]['data']
        cur.execute('update asl.lesson_attempts set status=%s,checkpoint=%s,completed_at=%s,earned_points=%s,revision=2 where user_id=%s and attempt_id=%s',
                    (completed['status'],Json(completed['checkpoint']),completed['completedAt'],completed['earnedPoints'],user,attempt['attemptId']))
        check('completed contract example earns 90 points and 90 percent', scalar('select score_percent from asl.lesson_attempts where user_id=%s',(user,)) == 90 and scalar('select total_points from asl.user_total_points where user_id=%s',(user,)) == 90)
        rejects('terminal attempt immutable', 'update asl.lesson_attempts set revision=3 where user_id=%s and attempt_id=%s', (user,attempt['attemptId']))
        rejects('completion ledger cannot duplicate', 'insert into asl.completion_ledger(user_id,attempt_id,points) values (%s,%s,90)', (user,attempt['attemptId']), '23505')
        insert_attempt(completed, str(uuid4()))
        check('replay earns points but not another distinct lesson', scalar('select total_points from asl.user_total_points where user_id=%s',(user,)) == 180 and scalar('select count(*) from asl.user_completed_lessons where user_id=%s',(user,)) == 1)
        check('learned signs use completed version snapshots', scalar('select count(*) from asl.user_learned_signs where user_id=%s',(user,)) == len(lesson['signs']))
        check('completion dates use saved timezone', str(scalar('select min(activity_date) from asl.user_activity_dates where user_id=%s',(user,))) == '2026-10-17')
        check('replay keeps one activity date and record streak', scalar('select count(*) from asl.user_activity_dates where user_id=%s',(user,)) == 1 and scalar('select record_streak from asl.user_streaks where user_id=%s',(user,)) == 1)

        collection = str(uuid4())
        cur.execute("insert into asl.personal_collections(user_id,id,name,revision) values (%s,%s,'Test',1)", (user,collection))
        cur.execute('update asl.personal_collections set deleted=true,revision=2 where user_id=%s and id=%s',(user,collection))
        rejects('collection tombstone cannot resurrect', 'update asl.personal_collections set deleted=false,revision=3 where user_id=%s and id=%s', (user,collection))
        rejects('report requires valid target', "insert into asl.user_reports(client_report_id,target_type,target_id,reason,message,request_hash) values (%s,'SIGN',%s,'OTHER','Test',%s)", (str(uuid4()),str(uuid4()),bytes(32)))
        report = contract['paths']['/api/v1/reports']['post']['requestBody']['content']['application/json']['examples']['quiz_wrong_answer']['value']
        report_sql = "insert into asl.user_reports(client_report_id,target_type,target_id,reason,message,request_hash,practice_context) values (%s,'PRACTICE_ITEM',%s,'WRONG_ANSWER','Test',%s,%s)"
        context = report['practiceContext']
        report_id = str(uuid4())
        cur.execute(report_sql, (report_id, report['targetId'], bytes(32), Json(context)))
        check('quiz report retains exact client snapshot', scalar('select practice_context from asl.user_reports where client_report_id=%s', (report_id,)) == context)
        rejects('quiz report requires context', report_sql, (str(uuid4()), report['targetId'], bytes(32), None))
        rejects('quiz report rejects mismatched item ID', report_sql, (str(uuid4()), str(uuid4()), bytes(32), Json(context)))
        rejects('quiz report rejects incomplete snapshot', report_sql, (str(uuid4()), report['targetId'], bytes(32), Json({'item': {'id': report['targetId']}})))
        rejects('sign report rejects practice context', "insert into asl.user_reports(client_report_id,target_type,target_id,reason,message,request_hash,practice_context) values (%s,'SIGN',%s,'OTHER','Test',%s,%s)", (str(uuid4()),lesson['signs'][0]['id'],bytes(32),Json(context)))
        cur.execute('insert into asl.sync_streams(user_id) values (%s)',(user,))
        for revision in [1,2]:
            cur.execute("insert into asl.sync_changes(user_id,kind,entity_key,revision,data) values (%s,'PREFERENCES','preferences',%s,'{}')",(user,revision))
        check('account change sequence assigned monotonically', scalar('select last_sequence from asl.sync_streams where user_id=%s',(user,)) == 2)
        rejects('sync history immutable', "update asl.sync_changes set data='{}' where user_id=%s", (user,))
        check('no premium without verified subscription', scalar('select count(*) from asl.user_entitlements where user_id=%s',(user,)) == 0)
        check('mobile policy matches contract', scalar("select setting_value->>'practiceMinimumDistinctLessons' from asl.app_settings where setting_key='mobile_policy'") == '3')
        policy = scalar("select setting_value from asl.app_settings where setting_key='mobile_policy'")
        check('daily lesson limit remains unlimited', 'dailyLessonLimit' in policy and policy['dailyLessonLimit'] is None)
        check('mobile policy version matches contract', policy['contractVersion'] == contract['info']['version'])
        check('scoring policy matches contract', scalar("select setting_value from asl.app_settings where setting_key='scoring_policy'") == lesson['scoringPolicy'])
        return {'status':'PASS', 'mode':'transactional trial' if trial else ('pending migrations rollback' if pending_migrations else 'installed schema'),
                'testedMigrations': tested_migrations,
                'postgresqlVersion':version, 'tables':33, 'views':6, 'functions':17,
                'triggers':trigger_count, 'checks':checks, 'fixtureWrites':'ROLLED_BACK',
                'migrationSha256':hashlib.sha256((MIGRATIONS/'V2__create_mobile_schema.sql').read_bytes()).hexdigest()}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, required=True)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument('--trial', action='store_true')
    modes.add_argument('--pending-migrations', action='store_true')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    conn = None
    try:
        conn = connect(args.env_file)
        conn.autocommit = False
        result = verify(conn, args.trial, args.pending_migrations)
    except (psycopg2.Error, AssertionError, ValueError, KeyError) as error:
        # Never print connection errors/DSNs/passwords or database row contents.
        print(json.dumps({'status':'FAIL', 'type':type(error).__name__,
                          'detail':str(error) if isinstance(error, AssertionError) else getattr(error,'pgcode',None)}))
        raise SystemExit(1)
    finally:
        if conn is not None:
            conn.rollback()
            conn.close()
    if args.report:
        args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(result,indent=2))
