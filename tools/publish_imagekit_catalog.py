"""Verify uploaded catalog videos, then optionally publish their DB records.

Default is a read-only audit. --apply publishes only checksum-verified videos,
their active signs, and nonempty collections whose media are all available.
ImageKit is only read; no upload, rename or deletion is performed.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
import os
from pathlib import Path
import time
from urllib.error import HTTPError, URLError
from urllib.parse import quote, urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen

import javaproperties

from verify_schema import ROOT, connect


def inventory(settings):
    key = settings.get('IMAGEKIT_PRIVATE_KEY', '').strip().strip('"\'')
    if not key:
        raise ValueError('Set IMAGEKIT_PRIVATE_KEY in backend/.env')
    auth = 'Basic ' + base64.b64encode((key + ':').encode()).decode()
    files = []
    for skip in range(0, 1000000, 1000):
        query = urlencode(dict(type='file', limit=1000, skip=skip, sort='ASC_CREATED'))
        request = Request('https://api.imagekit.io/v1/files?' + query,
                          headers={'Authorization': auth})
        with urlopen(request, timeout=30) as response:
            page = json.load(response)
        if not isinstance(page, list):
            raise ValueError('Unexpected ImageKit inventory response')
        files.extend(page)
        if len(page) < 1000:
            return {row['filePath'].lstrip('/'): row for row in files
                    if row.get('type') == 'file'}
    raise ValueError('ImageKit inventory exceeded the supported size')


def original_url(file, key):
    """Bypass CDN re-encoding so the delivered bytes match the DB checksum."""
    uri = urlsplit(file['url'])
    path = uri.path.removesuffix('/ik-video.mp4')
    suffix = '/' + key
    if uri.scheme != 'https' or uri.hostname != 'ik.imagekit.io' or not path.endswith(suffix):
        raise ValueError('Unexpected ImageKit URL or path')
    prefix = path[:-len(suffix)].rstrip('/')
    base = urlunsplit((uri.scheme, uri.netloc, prefix + '/tr:f-mp4,vc-h264/', '', ''))
    source = urlunsplit((uri.scheme, uri.netloc, prefix + '/tr:orig-true/' + quote(key, safe='/'), '', ''))
    return base, source


def verify_video(asset, file):
    if file is None:
        return {'id': asset['id'], 'storageKey': asset['storage_key'], 'error': 'NOT_UPLOADED'}
    if file.get('isPrivateFile') or file.get('isPublished') is False:
        return {'id': asset['id'], 'storageKey': asset['storage_key'], 'error': 'NOT_PUBLIC'}
    try:
        base, source = original_url(file, asset['storage_key'])
    except ValueError:
        return dict(id=asset['id'], storageKey=asset['storage_key'], error='INVALID_IMAGEKIT_PATH')
    url = base + quote(asset['storage_key'], safe='/')
    result = dict(id=asset['id'], storageKey=asset['storage_key'], url=url, sourceUrl=source, baseUrl=base)
    for attempt in range(3):
        try:
            request = Request(source, headers={'User-Agent': 'ASL-catalog-verifier/1.0'})
            with urlopen(request, timeout=30) as response:
                if response.status != 200 or response.headers.get_content_type() not in ('video/mp4', 'video/quicktime'):
                    raise ValueError('Unexpected video status or MIME type')
                digest, size, header = hashlib.sha256(), 0, b''
                while chunk := response.read(128 * 1024):
                    digest.update(chunk)
                    size += len(chunk)
                    if not header:
                        header = chunk[:32]
                    if size > 100 * 1024 * 1024:
                        raise ValueError('Video exceeds 100 MiB verification limit')
            if header[4:8] != b'ftyp':
                raise ValueError('Response is not an MP4 container')
            result.update(bytes=size, sha256=digest.hexdigest())
            if digest.hexdigest() != asset['sha256_checksum']:
                result['error'] = 'CHECKSUM_MISMATCH'
            elif size != file.get('size'):
                result['error'] = 'SIZE_MISMATCH'
            if not result.get('error'):
                request = Request(url, headers={'Range': 'bytes=0-65535', 'User-Agent': 'ASL-catalog-verifier/1.0'})
                with urlopen(request, timeout=30) as response:
                    result['deliveryStatus'] = response.status
                    result['deliveryMimeType'] = response.headers.get_content_type()
                    header = response.read(64)
                    if response.status not in (200, 206) or result['deliveryMimeType'] != 'video/mp4' or header[4:8] != b'ftyp':
                        raise ValueError('Delivery is not a playable MP4 response')
            return result
        except (HTTPError, URLError, TimeoutError, OSError, ValueError) as error:
            if attempt == 2:
                result['error'] = type(error).__name__
                result['httpStatus'] = getattr(error, 'code', None)
                return result
            time.sleep(attempt + 1)


def publish(connection, verified):
    """Run in one transaction, retaining all published IDs and metadata."""
    ids = [row['id'] for row in verified if not row.get('error')]
    with connection.cursor() as cursor:
        cursor.execute("select pg_advisory_xact_lock(hashtext('asl-imagekit-catalog-publication'))")
        # Lock/recheck checksum and path to avoid publishing a concurrently edited draft.
        cursor.execute('select id::text,storage_key,sha256_checksum from asl.media_assets where id=any(%s::uuid[]) for update', (ids,))
        expected = {row['id']: row for row in verified if not row.get('error')}
        for asset_id, key, checksum in cursor.fetchall():
            if (key, checksum) != (expected[asset_id]['storageKey'], expected[asset_id]['sha256']):
                raise ValueError('Asset changed during verification; rerun the audit')
        cursor.execute('''update asl.media_assets set published_at=now()
            where id=any(%s::uuid[]) and published_at is null and revoked_at is null''', (ids,))
        media_count = cursor.rowcount
        cursor.execute('''update asl.signs s set published_at=now()
            where s.published_at is null and s.retired_at is null
              and s.video_asset_id=any(%s::uuid[])
              and exists(select 1 from asl.media_assets m where m.id=s.video_asset_id
                  and m.published_at<=now() and m.revoked_at is null)''', (ids,))
        sign_count = cursor.rowcount
        cursor.execute('''update asl.collections c set published_at=now()
            where c.published_at is null and c.retired_at is null
              and exists(select 1 from asl.collection_signs cs join asl.signs s on s.id=cs.sign_id
                  where cs.collection_id=c.id and s.video_asset_id=any(%s::uuid[]) and s.retired_at is null)
              and not exists(select 1 from asl.collection_signs cs join asl.signs s on s.id=cs.sign_id
                  join asl.media_assets m on m.id=s.video_asset_id where cs.collection_id=c.id
                  and (s.published_at is null or s.published_at>now() or s.retired_at is not null
                       or m.published_at is null or m.published_at>now() or m.revoked_at is not null
                       or not(m.id=any(%s::uuid[]))))
              and (c.cover_asset_id is null or exists(select 1 from asl.media_assets m
                  where m.id=c.cover_asset_id and m.published_at<=now() and m.revoked_at is null))''', (ids, ids))
        return dict(publishedMedia=media_count, publishedSigns=sign_count, publishedCollections=cursor.rowcount)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--env-file', type=Path, default=ROOT / 'backend/.env')
    parser.add_argument('--report', type=Path, default=ROOT / '.tools/imagekit/publication-report.json')
    parser.add_argument('--workers', type=int, default=6, choices=range(1, 9))
    parser.add_argument('--apply', action='store_true')
    args = parser.parse_args()
    with args.env_file.open(encoding='utf-8-sig') as stream:
        settings = javaproperties.load(stream)
    settings.update(os.environ)
    files = inventory(settings)
    connection = connect(args.env_file)
    try:
        # Do not keep a DB transaction open during network checks.
        connection.set_session(readonly=True)
        with connection:
            with connection.cursor() as cursor:
                cursor.execute("select id::text,storage_key,sha256_checksum from asl.media_assets where storage_key like 'asl-videos/%%' and revoked_at is null")
                assets = [dict(zip(['id', 'storage_key', 'sha256_checksum'], row)) for row in cursor.fetchall()]
        verified = []
        with ThreadPoolExecutor(max_workers=args.workers) as executor:
            pending = [executor.submit(verify_video, asset, files.get(asset['storage_key'])) for asset in assets]
            for future in as_completed(pending):
                row = future.result()
                verified.append(row)
                if len(verified) % 100 == 0 or len(verified) == len(assets):
                    print(f'Checked {len(verified)}/{len(assets)}; failures={sum(bool(r.get("error")) for r in verified)}', flush=True)
        bases = {row['baseUrl'] for row in verified if not row.get('error')}
        report = dict(applied=False, total=len(assets), verified=sum(not row.get('error') for row in verified),
                      baseUrls=sorted(bases), assets=sorted(verified, key=lambda r: r['storageKey']))
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')
        if any(row.get('error') for row in verified):
            raise ValueError('Some videos failed verification; no database changes made. See report.')
        if len(bases) != 1:
            raise ValueError('Expected exactly one delivery base URL')
        configured = settings.get('MEDIA_PUBLIC_BASE_URL', '').rstrip('/') + '/'
        if args.apply:
            if configured != next(iter(bases)):
                raise ValueError('Set MEDIA_PUBLIC_BASE_URL to the report baseUrls value before --apply')
            connection.set_session(readonly=False)
            with connection:
                report.update(publish(connection, verified))
            report['applied'] = True
            args.report.write_text(json.dumps(report, indent=2), encoding='utf-8')
        print(json.dumps({k: v for k, v in report.items() if k != 'assets'}), flush=True)
    finally:
        connection.close()


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never print DB connection strings, credentials or authenticated request headers.
        print(json.dumps(dict(status='FAILED', errorType=type(error).__name__,
                              httpStatus=getattr(error, 'code', None))), flush=True)
        raise SystemExit(1)
