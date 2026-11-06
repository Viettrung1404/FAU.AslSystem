import hashlib
from io import BytesIO
import unittest
from unittest.mock import patch

from publish_imagekit_catalog import original_url, publish, verify_video


class VideoResponse(BytesIO):
    status = 200

    @property
    def headers(self):
        from email.message import Message
        value = Message()
        value['Content-Type'] = 'video/mp4'
        return value


class PublicationTest(unittest.TestCase):
    def setUp(self):
        self.data = b'\x00\x00\x00\x18ftypmp42' + b'video-bytes'
        self.asset = dict(id='asset', storage_key='asl-videos/topic/a.mp4',
                          sha256_checksum=hashlib.sha256(self.data).hexdigest())
        self.file = dict(url='https://ik.imagekit.io/account/asl-videos/topic/a.mp4?updatedAt=123',
                         size=len(self.data), isPublished=True, isPrivateFile=False)

    def test_verifies_original_bytes_instead_of_the_automatically_reencoded_video(self):
        with patch('publish_imagekit_catalog.urlopen', side_effect=[VideoResponse(self.data), VideoResponse(self.data)]) as request:
            result = verify_video(self.asset, self.file)
        self.assertNotIn('error', result)
        self.assertEqual(result['sha256'], self.asset['sha256_checksum'])
        self.assertEqual(request.call_args_list[0].args[0].full_url,
                         'https://ik.imagekit.io/account/tr:orig-true/asl-videos/topic/a.mp4')
        self.assertEqual(request.call_args_list[1].args[0].full_url,
                         'https://ik.imagekit.io/account/tr:f-mp4,vc-h264/asl-videos/topic/a.mp4')

    def test_quicktime_upload_url_suffix_is_not_part_of_the_storage_key(self):
        self.file['url'] = self.file['url'].replace('.mp4?', '.mp4/ik-video.mp4?')
        base, source = original_url(self.file, self.asset['storage_key'])
        self.assertEqual(base, 'https://ik.imagekit.io/account/tr:f-mp4,vc-h264/')
        self.assertTrue(source.endswith('/asl-videos/topic/a.mp4'))

    def test_mismatched_bytes_do_not_qualify_for_publication(self):
        self.asset['sha256_checksum'] = '0' * 64
        with patch('publish_imagekit_catalog.urlopen', return_value=VideoResponse(self.data)):
            self.assertEqual(verify_video(self.asset, self.file)['error'], 'CHECKSUM_MISMATCH')

    def test_missing_and_private_uploads_never_get_requested(self):
        with patch('publish_imagekit_catalog.urlopen') as request:
            self.assertEqual(verify_video(self.asset, None)['error'], 'NOT_UPLOADED')
            self.file['isPrivateFile'] = True
            self.assertEqual(verify_video(self.asset, self.file)['error'], 'NOT_PUBLIC')
        request.assert_not_called()

    def test_url_must_match_the_account_file_path(self):
        self.file['url'] = 'https://other.example/asl-videos/topic/a.mp4'
        with self.assertRaises(ValueError):
            original_url(self.file, self.asset['storage_key'])

    def test_concurrently_changed_asset_aborts_before_any_updates(self):
        from unittest.mock import MagicMock
        connection = MagicMock()
        cursor = connection.cursor.return_value.__enter__.return_value
        cursor.fetchall.return_value = [('asset', 'changed.mp4', '0' * 64)]
        row = dict(id='asset', storageKey=self.asset['storage_key'], sha256=self.asset['sha256_checksum'])
        with self.assertRaises(ValueError):
            publish(connection, [row])
        self.assertFalse(any('update ' in call.args[0].lower() for call in cursor.execute.call_args_list))


if __name__ == '__main__':
    unittest.main()
