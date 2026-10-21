# Admin video publishing

Open **Creator studio → Open video publishing studio**. A real OIDC admin session is required; the static preview displays the workflow but cannot upload without the deployed Spring API and Cloudflare credentials.

## Workflow

1. Create a live draft with name, synopsis and genre.
2. Select its original video (up to 30 GB) and a maximum duration (up to six hours). The server reserves a signed-only Cloudflare Stream upload. Cloudflare storage reservation and delivery charges apply; compute free tiers do not make video delivery free.
3. The browser sends 8 MiB tus chunks directly to Cloudflare. The API never receives the video body. Progress shows transferred bytes; Pause aborts the in-flight chunk, Resume reads the committed provider offset with HEAD and continues. Keep the dialog open: the capability and File object are held only in memory. Closing the dialog stops transfer, and a page reload cannot currently resume the same slot.
4. Refresh processing to query Cloudflare's actual encoding state and percentage. Stream automatically transcodes supported inputs into available resolutions/bitrates and packages HLS/DASH. The admin does not select a custom encoding ladder in this release. Source quality limits output quality.
5. Preview adaptive playback using the provider's protected iframe player. It fetches CDN segments, adjusts bitrate based on connection and buffer conditions, and exposes quality controls. Preview tokens expire after 15 minutes; reopen the preview to renew. No LLM is involved in adaptive bitrate selection.
6. Confirm rights and captions/accessibility review. Publish is enabled only after ready-to-stream and 100% encoding; the server rechecks Cloudflare before changing the catalog status. Review checkboxes record an admin attestation, not automated legal/caption validation. Caption upload, territory licensing controls and automated quality checks are separate future features.

## Deployment

Apply `server/migrations/004_video_publishing.sql` after 003. Set `CF_ACCOUNT_ID` (32 hex characters), `CF_STREAM_TOKEN` (scoped Stream read/write permissions), and `CF_CUSTOMER_CODE` (the customer code without the `customer-` prefix). Keep the API token exclusively on the server. Use HTTPS for the app and trusted OIDC identity provisioning. Configure same-origin `/api` routing; static Sites alone does not execute Java or provision Cloudflare.

The server reserves tus uploads using Cloudflare's direct-user endpoint with byte length, expiry, maximum duration and `requiresignedurls` metadata. Only trusted HTTPS provider upload destinations are accepted. Provider URLs/tokens are capabilities: do not log them. Account tokens are never returned. Listings and upload/preview responses use `Cache-Control: no-store`. Database row locks prevent simultaneous allocation on a single draft; uploaded titles cannot silently replace published assets.

Endpoints (all admin-only):

| Endpoint | Responsibility |
| --- | --- |
| `GET /api/admin/videos` | Configuration status and latest 100 titles/media states |
| `POST /api/admin/titles` | Existing audited live draft creation |
| `POST /api/admin/videos/{id}/upload` | Reserve direct tus URL from byte length and maximum duration |
| `POST /api/admin/videos/{id}/refresh` | Query provider; update processing state and completion percentage |
| `POST /api/admin/videos/{id}/preview` | Verify readiness and issue short-lived iframe/HLS/DASH preview URLs |
| `POST /api/admin/videos/{id}/publish` | Reverify all renditions and reviews, publish and audit |

## Code responsibilities and limits

`CloudflareVideoClient`: bounded-time provider API calls, tus metadata, signed playback and safe capability destinations. `VideoPublishingService`: admin roles, draft/upload/processing/review/published transitions, transactional audit. `VideoPublishingController`: validated request contracts and uncached responses. `video-studio.js`: protected forms, progress, bounded-memory upload and explicit pause/resume. Actual video format/duration validity is enforced by Cloudflare processing, not filename acceptance.

Provider API creation cannot share a transaction with PostgreSQL. A provider allocation followed by database failure may leave an orphan asset; reconcile expired/unreferenced uploads in Cloudflare operations. Failed/abandoned uploads currently require operator cleanup and a new draft; automated replacement, removal, webhook reconciliation and retry across browser reloads are not implemented. Status refresh is explicit polling, not an unsigned webhook. No live Cloudflare upload, real encoding job, paid CDN transfer, or GB-scale load was executed without an account.

## Validation

Tests cover unauthenticated endpoint rejection, admin role enforcement, upload byte/duration bounds, required review, duplicate allocation, incomplete-encoding publication rejection, signed-only provider metadata, provider destination validation, and a mocked 5 GB reservation (metadata only). Frontend tests cover trusted destinations and tus continuation at the provider offset. PostgreSQL pipeline tests use mocked Cloudflare responses to verify state transitions and audit rows; they do not assert real media processing.

Reference: [Cloudflare direct creator uploads](https://developers.cloudflare.com/stream/uploading-videos/direct-creator-uploads/).
