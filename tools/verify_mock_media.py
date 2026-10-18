"""Check real fixture media over HTTPS; optional decoding uses OpenCV and Pillow."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import struct
import tempfile
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[2]
MAX_BYTES = 20 * 1024 * 1024


def inspect(asset, decode=False):
    url = asset['url']
    result = {'url': url, 'expectedMimeType': asset['mimeType']}
    try:
        with urlopen(Request(url, headers={'Origin': 'http://localhost:4010'}), timeout=25) as response:
            data = response.read(MAX_BYTES + 1)
            mime = response.headers.get_content_type()
            result.update(status=response.status, mimeType=mime, bytes=len(data),
                          corsAllowOrigin=response.headers.get('Access-Control-Allow-Origin'))
        assert result['status'] == 200, 'Full GET must return 200'
        assert 0 < len(data) <= MAX_BYTES, 'Empty or oversized fixture'
        assert mime == asset['mimeType'], 'Content-Type differs from manifest'
        if mime == 'video/mp4':
            assert data[4:8] == b'ftyp' and b'moov' in data and b'mdat' in data, 'Not a complete MP4'
        elif mime == 'image/png':
            assert data.startswith(b'\x89PNG\r\n\x1a\n') and b'IEND' in data[-16:], 'Not a complete PNG'
            result['dimensions'] = list(struct.unpack('>II', data[16:24]))
        else:
            raise AssertionError('Unsupported fixture media type')
        result['sha256'] = hashlib.sha256(data).hexdigest()
        with urlopen(Request(url, headers={'Range': 'bytes=0-1023'}), timeout=25) as response:
            part = response.read(1025)
            result.update(rangeStatus=response.status, contentRange=response.headers.get('Content-Range'))
            assert response.status == 206, 'Server does not support requested byte range'
            assert result['contentRange'] == f'bytes 0-1023/{len(data)}', 'Unexpected Content-Range'
            assert part == data[:1024], 'Range response differs from full file'
        if decode:
            if mime == 'video/mp4':
                import cv2
                with tempfile.TemporaryDirectory(prefix='asl-media-check-') as folder:
                    path = Path(folder) / 'clip.mp4'
                    path.write_bytes(data)
                    cap = cv2.VideoCapture(str(path))
                    try:
                        fps = cap.get(cv2.CAP_PROP_FPS)
                        frames = cap.get(cv2.CAP_PROP_FRAME_COUNT)
                        assert fps > 0 and frames > 0, 'No video frames'
                        duration = round(1000 * frames / fps)
                        decoded = 0
                        while True:
                            ok, _ = cap.read()
                            if not ok:
                                break
                            decoded += 1
                        assert decoded == round(frames), 'Could not decode all frames'
                        assert abs(duration - asset['durationMs']) <= 50, 'Fixture durationMs differs from video'
                        result.update(durationMs=duration, framesDecoded=decoded, decode='PASS')
                    finally:
                        cap.release()
            else:
                from io import BytesIO
                from PIL import Image
                with Image.open(BytesIO(data)) as image:
                    image.load()
                result['decode'] = 'PASS'
        result['result'] = 'PASS'
    except Exception as error:
        result.update(result='FAIL', error=str(error))
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--decode', action='store_true', help='Also decode every frame/image; requires opencv-python and Pillow')
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    manifest = json.loads((ROOT / 'api-contract/examples/mock-media.json').read_text(encoding='utf-8'))
    unique = {asset['url']: asset for asset in manifest['assets']}
    with ThreadPoolExecutor(max_workers=6) as pool:
        results = list(pool.map(lambda asset: inspect(asset, args.decode), unique.values()))
    report = {
        'checkedAt': datetime.now(timezone.utc).isoformat(),
        'status': 'PASS' if all(r['result'] == 'PASS' for r in results) else 'FAIL',
        'assetIds': len(manifest['assets']), 'uniqueUrls': len(unique),
        'scope': 'HTTP, MIME, file signatures, byte ranges' + (', complete media decoding and video duration' if args.decode else ''),
        'limitation': 'External URLs can change. HTTP/decode checks do not prove Flutter player integration or ASL instructional accuracy. Missing CORS headers may affect Flutter Web.',
        'results': results,
    }
    output = json.dumps(report, indent=2)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(output + '\n', encoding='utf-8')
    print(output)
    raise SystemExit(0 if report['status'] == 'PASS' else 1)
