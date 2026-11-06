# Publish an existing ImageKit catalog

The database already contains collections, membership, signs and media metadata.
Uploading files does not change their database publication status.

Set `IMAGEKIT_PRIVATE_KEY` in the ignored `backend/.env` for the account that holds
the uploaded videos. The key is only used by the publication tool to list files.
The running backend serves public delivery URLs without that key.

From the workspace root, audit without changing the database:

```powershell
python -m pip install -r backend/tools/database-requirements.txt
python backend/tools/publish_imagekit_catalog.py
```

The tool matches exact `storage_key` paths to ImageKit files. It downloads original
videos and checks their SHA-256 against the database, then checks MP4/H.264 delivery
with a range request. Original QuickTime files are accepted for source verification
and delivered as MP4. No files are uploaded, renamed or deleted. An audit failure
prevents all publication writes.

Read `baseUrls` in `.tools/imagekit/publication-report.json`, set
`MEDIA_PUBLIC_BASE_URL` in `backend/.env` to that value, then run:

```powershell
python backend/tools/publish_imagekit_catalog.py --apply
```

Publication updates draft media, their active signs and nonempty collections with
all media ready, in one transaction. It never changes Premium flags, resurrects
retired/revoked records or edits published media metadata. Existing published records
are preserved; a repeat run is idempotent. Four empty fingerspelling collections in
the current dataset remain drafts until they have content.

Restart the backend after changing `.env`. Keep `API_MODE=server` in `mobile/.env`
and make sure `SERVER_API_URL` points to the computer's current LAN address when
testing on a phone. Stop and relaunch Flutter after changing its `.env`.

API checks:

```text
GET /api/v1/collections?limit=100
GET /api/v1/collections/{collectionId}/signs?limit=100
GET /api/v1/signs/{signId}
POST /api/v1/media/urls   {"assetIds":["..."]}
```

Follow `nextCursor` when it is present; the default page size is 20. Collection
sharing additionally uses `ASL_SHARE_ORIGIN`, while dictionary pagination requires
`ASL_DICTIONARY_CURSOR_SECRET`. These are independent of the ImageKit delivery host.

ImageKit references: [list files](https://github.com/imagekit-developer/imagekit-php#1-list-and-search-files),
[original delivery](https://imagekit.io/docs/core-delivery-features),
[MP4/H.264 conversion](https://imagekit.io/docs/video-optimization).
