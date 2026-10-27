"""Idempotent development curriculum. Explicit command only; never runs in production startup.

Research media comes from the existing contract manifest (Pocket Sign), not production
licensed assets. Download each unique URL to compute a real checksum before committing.
"""
import argparse
import hashlib
import json
from pathlib import Path
from urllib.request import urlopen, Request

from psycopg2.extras import Json
from verify_schema import connect, ROOT

FIXTURES = ROOT / 'backend/src/main/resources/development'
UNIT_ID = '46627d30-c686-59b7-a40b-bfe91269f055'


def seed(connection):
    lessons = json.loads((FIXTURES / 'lessons.json').read_text(encoding='utf8'))
    assets = json.loads((FIXTURES / 'media.json').read_text(encoding='utf8'))
    with connection.cursor() as cursor:
        cursor.execute("select pg_advisory_xact_lock(hashtext('asl-development-learning-seed'))")
        cursor.execute('select id from asl.lesson_versions where id = any(%s::uuid[])', ([l['versionId'] for l in lessons],))
        existing = {str(r[0]) for r in cursor.fetchall()}
        if len(existing) == len(lessons):
            return {'status': 'UNCHANGED', 'lessons': len(lessons)}
        checksums = {}
        for asset in assets:
            if asset['url'] not in checksums:
                with urlopen(Request(asset['url'], headers={'User-Agent': 'ASL-development-fixture/1.0'}), timeout=30) as response:
                    checksums[asset['url']] = hashlib.sha256(response.read()).hexdigest()
            cursor.execute('''insert into asl.media_assets(id,storage_key,mime_type,duration_ms,sha256_checksum,rights_reference,published_at)
                values (%s,%s,%s,%s,%s,%s,now()) on conflict (id) do nothing''',
                (asset['assetId'], 'development/'+asset['assetId'], asset['mimeType'], asset['durationMs'], checksums[asset['url']],
                 'DEVELOPMENT RESEARCH ONLY: '+asset['sourcePage']))
        cursor.execute("insert into asl.units(id,title,position,published_at) values (%s,'Getting started · Demo',coalesce((select max(position)+1 from asl.units),0),now()) on conflict(id) do nothing", (UNIT_ID,))
        for index, lesson in enumerate(lessons):
            if lesson['versionId'] in existing:
                continue
            cursor.execute('insert into asl.lessons(id,unit_id,position) values (%s,%s,%s) on conflict(id) do nothing', (lesson['lessonId'], UNIT_ID, index))
            cursor.execute('insert into asl.lesson_versions(id,lesson_id,version_no,title,lesson_type) values (%s,%s,1,%s,%s)',
                           (lesson['versionId'], lesson['lessonId'], lesson['title'], lesson['type']))
            for position, sign in enumerate(lesson['signs']):
                cursor.execute("insert into asl.signs(id,name,content_kind,video_asset_id,published_at) values (%s,%s,'WORD',%s,now()) on conflict(id) do nothing", (sign['id'], sign['name'], sign['video']['assetId']))
                cursor.execute("insert into asl.lesson_version_signs values (%s,%s,%s,'WORD',%s,%s)", (lesson['versionId'], sign['id'], sign['name'], sign['video']['assetId'], position))
            for e in lesson['exercises']:
                cursor.execute('insert into asl.lesson_exercises(id,lesson_version_id,position,exercise_type,prompt,payload,answer_key,points_per_correct) values (%s,%s,%s,%s,%s,%s,%s,%s)',
                               (e['exerciseId'], lesson['versionId'], e['position'], e['type'], e['prompt'], Json(e['payload']), Json(e['answerKey']) if e['answerKey'] is not None else None, e['pointsPerCorrect']))
                for sid in e['signIds']:
                    cursor.execute('insert into asl.exercise_signs values (%s,%s,%s)', (lesson['versionId'], e['exerciseId'], sid))
                for slot, asset in e['media'].items():
                    cursor.execute('insert into asl.lesson_exercise_media values (%s,%s,%s,%s)', (lesson['versionId'], e['exerciseId'], slot, asset['assetId']))
            for t in lesson['conversationTurns']:
                cursor.execute('insert into asl.conversation_turns(id,lesson_version_id,position,speaker_side,gloss_caption,english_translation,video_asset_id) values (%s,%s,%s,%s,%s,%s,%s)',
                               (t['turnId'], lesson['versionId'], t['position'], t['speakerSide'], t['glossCaption'], t['englishTranslation'], t['video']['assetId']))
            if index:
                cursor.execute('insert into asl.lesson_prerequisites values (%s,%s) on conflict do nothing', (lesson['lessonId'], lessons[index-1]['lessonId']))
            cursor.execute('update asl.lesson_versions set published_at=now(),is_current=true where id=%s', (lesson['versionId'],))
        return {'status': 'SEEDED', 'lessons': len(lessons), 'exerciseTypes': len({e['type'] for l in lessons for e in l['exercises']}), 'mediaAssets': len(assets)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT/'backend/.env')
    args = parser.parse_args()
    connection = None
    try:
        connection = connect(args.env_file)
        with connection:
            result = seed(connection)
        print(json.dumps(result))
    except Exception as error:
        # Connection details and database data must not be printed.
        print(json.dumps({'status': 'FAILED', 'errorType': type(error).__name__, 'sqlState': getattr(error, 'pgcode', None)}))
        raise SystemExit(1)
    finally:
        if connection is not None:
            connection.close()
