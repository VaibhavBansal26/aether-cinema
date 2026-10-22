# Studio, membership, and viewing operations

## Viewer experience

A series collection is never passed to the playback-session endpoint. Home Watch opens its episode list; episode cards acquire their own protected playback leases. The responsive cinema dialog uses a 16:9 video canvas with `object-fit: contain`, so portrait phones and desktop screens retain the original picture without cropping.

Published titles use `/api/artwork/{id}`. An administrator may upload a validated JPEG (maximum 250 KB, 1920×1080). The first episode artwork is inherited by its series unless that series already has artwork. Without a JPEG, the endpoint generates a branded SVG cover from escaped catalog metadata. Draft and unreleased artwork is unavailable publicly. The demo stores one bounded JPEG per title in PostgreSQL and exposes cacheable artwork; a large catalog should move these assets behind a dedicated image CDN/object store.

`tools/encode_hls.py` includes `thumbnail.jpg` in its output folder. The R2 uploader separates this JPEG from the video reservation, uploads HLS media directly to the private gateway, then saves the thumbnail. The video storage caps still apply to HLS renditions. Thumbnail save failures leave the transferred video intact and allow a manual JPEG upload during review.

Membership screens show the current plan, status, stream allowance, renewal/end date when supplied by Stripe, and the subscribed price. Active members use the customer portal to change plans; the current plan is disabled instead of offering duplicate checkout. Stripe remains in the deployment's configured mode (the current demo is sandbox only).

## Guided publishing

1. **Details:** select an existing title or expand creation. Episodes require a parent collection, season and episode.
2. **Upload:** choose the encoded folder; see percent, bytes, speed and file count. Reservations are discovered automatically. Retrying uses the same reserved bundle. Step navigation is locked while uploading.
3. **Review:** check distribution rights and accessibility, optionally supply a JPEG thumbnail.
4. **Publish:** choose immediate or scheduled release. Publishing a series requires a ready episode. Published titles explain their state and link episodes to their parent collection. Successful publication refreshes the browsing catalog.

Closing the studio aborts the current browser transfer. Reopen, select the same draft and folder, and retry to reuse the reservation. A retry retransmits existing segment names idempotently; it does not claim to skip committed bytes. The free host can sleep and release execution can be late, but backend date guards prevent early playback.

## Administration

`GET /api/admin/analytics` returns active unexpired playback leases, distinct active accounts, starts during 24 hours, active/trialing subscribers, top titles by starts, and current streams per title. Active lease counts can lag a disconnect by up to the 120-second lease duration. Starts are not completed views, and test playback also contributes. Queries are bounded in returned rows and supported by lease/start indexes. The dashboard refreshes every 30 seconds while visible and also supports manual refresh, with a timestamp.

`GET /api/admin/plans` lists current offers and retained price-version counts. `POST /api/admin/plans` accepts a stable request UUID, ID, display name, currency, minor-unit monthly amount and stream limit. The demo supports up to eight offers and one to four streams, with INR/USD/EUR/GBP. A change creates an immutable Stripe price. It never changes an existing Stripe subscription. Historic price-to-plan/stream-limit mappings remain available for webhook reconciliation. Repeated identical requests reuse the result; reusing an ID for different input fails. Pending checkout intents freeze their selected price, protecting retry correctness across offer changes. Current-offer cache signatures change across replicas when the stored price changes.

Customer-portal sessions include current offers and the member's historical price, supporting explicit customer-approved plan changes and cancellation. Admin price changes and artwork updates create audit records. APIs enforce the database-owned admin role; client interface state cannot grant authority.

## Deployment and verification

Apply migration `011_studio_operations.sql` before deploying the matching server. Old migrations remain immutable. Tests cover artwork privacy/inheritance and decoding limits, analytics lease semantics, price-version idempotency, grandfathered stream limits and frozen checkout prices. Browser verification covers published series routing, player proportions, membership state, admin surfaces and wizard validation. A CDN read or a playback lease alone is not evidence of sustained million-user capacity.


Publishing workflow safeguards: the selected title shows a prominent PUBLISHED, SCHEDULED or DRAFT badge. Add episode locks the parent series and content type; a successful draft creation selects that episode. Details, upload and review gates must pass before advancing. Episode numbers are unique among non-archived episodes of the same series and season.

DELETE /api/admin/titles/{id} archives the title, ends its playback leases and hides it from the catalog and publishing selector. It preserves media, history and storage reservations. Collections must have their episodes archived first. Migration 012 permits reuse of archived episode numbers. No media storage is reclaimed by this operation.
