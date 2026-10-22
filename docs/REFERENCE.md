# Feature and function reference

## Frontend (`dist/app.js` and `dist/catalog.js`)

| Function / data | Contract and behavior |
|---|---|
| `catalog`, `featuredIds` | Fictional title metadata, original-art paths, editorial ratings, alt text and featured selections. No external catalog provider or licensed feature streams are implied. |
| `$`, `$$`, `icon`, `titleById`, `icons` | DOM query helpers, trusted Lucide icon markup, catalog lookup, and icon-library hydration. |
| `persist` | Save preview state in `aether-v1`; storage failures produce a user-visible status instead of blocking catalog browsing. No auth/payment credentials are stored. |
| `toast` | Screen-reader-announced transient status, with previous timer cancellation. |
| `cleanupMedia` | Flush saved time/sound, pause/release the sample, then refresh Continue watching before dialog replacement/closure. |
| `modal`, `closeModal` | Native accessible dialog with a dynamic label, close/Escape handling and media cleanup. |
| `recommendationReason` | Explain genre affinity from actual device likes; otherwise label an editorial pick. No fabricated popularity/match percentage. |
| `syncCollection` | Update all copies of each title's collection button, its accessible pressed state, and header count. Preserves shelf scroll positions. |
| `toggleSaved` | Add/remove a catalog ID, persist, synchronize controls and update the list view. |
| `card` | Keyboard-operable title card, separate collection action, lazy artwork, optional ranked number or real progress bar. |
| `shelf` | Horizontal scroll shelf with measured scroll steps, disabled boundary arrows, view-all action and responsive ResizeObserver. |
| `motion` | Respect reduced-motion preferences for browser scrolling. |
| `genreSection` | Build five curated genre tiles with direct filtered-catalog routes. |
| `render` | Build home shelves or filtered results from view/query/genre/originals state. Release old shelf observers before rerender. Only actual saved playback creates Continue watching. |
| `setBrowse` | Switch catalog context, reset query and scroll results into view. |
| `feature` | Switch hero title, art, synopsis, metadata, collection state and accessible selection. No automatic rotation or autoplay. Long titles use an explicit smaller type size. |
| `details` | Cinematic art, synopsis, mood, metadata, sample action, like/list controls, recommendation explanation and series availability disclosure. |
| `play` | Native W3C-hosted Big Buck Bunny trailer; restore time/volume/mute/rate, checkpoint at 5s and pause/end/settings changes; surface load errors. A single MP4, not actual adaptive HLS. |
| `plans` | Membership comparison with clear unconnected Stripe messaging; never simulates a charge or active subscription. |
| `admin` | Native required/length-constrained form saves a device-only draft and shows workflow/production-admin boundaries. |
| `profile` | Display local library statistics, open My list, reset recommendation preferences or clear local history. |

Navigation, genre/search, featured-selector, hero save/play/detail, membership, editorial collection and studio listeners wire all primary visible actions. `/` focuses search except inside text controls or an open dialog. The header receives a solid background on scroll. All state remains browser-local until the Spring Boot integration is provisioned and wired.

Additional modules: `dist/api-client.js`, `dist/recommendation-controls.js`, `refreshRecommendations`, Java recommendation classes and Python functions are documented in [RECOMMENDATIONS.md](RECOMMENDATIONS.md).

## Pure algorithms (`dist/core.js`)

- `recommend(catalog, likes, progress)` returns a new sorted array. Each liked genre adds affinity; editorial rating breaks cold start; watch-progress fractions also inform genre affinity; completed titles receive a penalty. No model training or external AI call.
- `qualityFor(mbps, saveData)` returns a conservative startup quality cap (360/480/720/1080). Unknown networks start at 480. It does not switch the sample video.
- `resumeAt(seconds, duration)` clamps valid positions to the timeline and rejects negative/nonfinite history. Player calls it after metadata is available.

## Spring Boot API (`server/src/main/java/com/aether/api/`)

| Class / function | Contract and purpose |
|---|---|
| `AetherApplication.main` | Starts the JVM API; never proxies media bytes. |
| `SecurityConfig.security` | Stateless Bearer security; public liveness/health and POST Stripe webhook only. CSRF disabled because API does not use cookies/session authentication. |
| `SecurityConfig.jwtDecoder` / `audienceValidator` | Verify JWKS signature, issuer, time and API audience; application roles come from PostgreSQL. |
| `IdentityService.requireUser` | Resolve OIDC subject to provisioned user. Unknown identity is 403. |
| `IdentityService.requireProfile` | Verify profile belongs to authenticated user; 404 for non-owned profiles. |
| `IdentityService.requireAdmin` | Require server-owned `admin` role before draft writes. |
| `Requests.Progress`, `Reaction`, `Checkout`, `Draft` | Bean-validated DTOs with required fields, lengths, enums and finite timeline bounds. |
| `LibraryService.catalog` | Bounded published-only catalog with compound timestamp/UUID pagination. |
| `LibraryService.progress` / `saveProgress` | Owned profile history read (latest 100) and upsert with sound state. |
| `LibraryService.saveReaction` | Owned profile likes and collection flags. |
| `LibraryService.createDraft` | Transactional admin draft + audit row. |
| `BillingService.customer` | Resolve only trusted customer mappings; missing onboarding is 409. |
| `BillingService.checkout` / `portal` | Official Stripe Java SDK creates hosted sessions with server-owned prices/origin/customer. |
| `BillingService.handleWebhook` | Verify raw payload signature before parsing; lock by subscription, dedupe event, fetch latest Stripe state, atomically update mapping/event row. Missing customer mapping fails for retry. |
| `PlaybackService.authorize` | Verify active/trialing entitlement and published title, request 15-minute Cloudflare signed token with bounded HTTP timeouts; return HLS URL. |
| `ApiController` | Map validated requests and authenticated principals to services. Playback responses are `Cache-Control: no-store`. |
| `BodyLimitFilter.doFilterInternal` | Enforce 32 KiB API / 1 MiB webhook body bounds, including chunked requests. |
| `ErrorHandler` | Return safe 400/409/provider errors; hide SQL, provider payloads and credential values. |

| HTTP endpoint | Body / behavior |
|---|---|
| `GET /health` | Public liveness only. |
| `GET /actuator/health/liveness` | Infrastructure liveness. |
| `GET /actuator/health/readiness` | Readiness including PostgreSQL; protect at the gateway. |
| `GET /api/catalog?limit=20` | Published metadata, limit 1–50. Next page supplies both `before` (ISO timestamp) and `beforeId` (UUID) from the last returned title; ordering timestamp DESC, id DESC. |
| `GET /api/profiles/{profileId}/progress` | Latest 100 progress records for an owned profile. |
| `PUT /api/profiles/{profileId}/progress/{titleId}` | Required `position`, `duration`, `volume`, `muted`; 204. |
| `PUT /api/profiles/{profileId}/reactions/{titleId}` | Required `liked`, `saved` booleans; 204. |
| `POST /api/playback/{titleId}` | Active entitlement → signed HLS URL. |
| `POST /api/checkout` | `plan`: essential/cinema/collective → hosted Checkout URL. |
| `POST /api/billing/portal` | Hosted billing portal for current user's Stripe customer. |
| `POST /api/stripe/webhook` | Raw body + `Stripe-Signature`; no OIDC needed; 204 after verification/reconciliation. |
| `POST /api/admin/titles` | `name`, `synopsis`, `genre` → 201 with draft ID. |

Business `/api` endpoints require Bearer authentication and an application user. Exceptions are the signed webhook, public auth configuration, and five allowlisted identity proxy routes. Managed authentication and viewer onboarding are connected on the combined Render deployment. Profile/UUID/type errors return 400; non-owned profile returns 404; insufficient admin/entitlement returns 403. Missing records/conflicting foreign keys return 409. Unexpected errors return generic 500.

Not implemented: Stripe customer provisioning, profile CRUD, complete production catalog/artwork wiring, concurrency leases, DRM, territory rights enforcement, captions playback UI, deletion/export and live billing tests. Admin upload/processing/review/publication is implemented but awaits real Cloudflare provider verification. Progress is last-arriving-write wins; add session sequencing for concurrent devices. Define one-subscription-per-customer policy, reject duplicate purchases and add Checkout idempotency before collecting payments. Distributed rate limiting is a required deployment responsibility. Third-party Stripe/Cloudflare integrations need sandbox/live end-to-end verification.


## Managed identity additions

| Entry point | Behavior |
|---|---|
| `GET /api/auth/config` | Public, uncached enabled flag and relative identity proxy URL. |
| `POST /api/session/bootstrap` | Verified JWT → idempotent viewer user/Main profile, published title ID mapping; uncached. |
| `/api/identity/sign-in/email`, `/sign-up/email`, `/sign-out` | POST-only fixed upstream routes; exact Origin check, limited JSON, first-party managed cookies. |
| `/api/identity/get-session`, `/token` | GET-only fixed upstream routes; cookie session lookup and short-lived JWT issuance. |
| `IdentityProxyController.identityCookies` | Forward only managed identity cookies. |
| `IdentityProxyController.firstPartyCookie` | Remove upstream Domain, set SameSite=Lax; preserve Secure/HttpOnly. |
| `ManagedEd25519Decoder.decode` | Verify pinned EdDSA, JWKS signature, issuer/audience/time and mandatory subject/expiry. |
| `AccountService.bootstrap` | Advisory-lock subject onboarding; preserve operator roles; ignore provider role claims. |
| `account-controls.initializeAuth` | Fetch public config once; lazy-load SDK only when enabled. |
| `auth-entry.initializeAuth` | Create managed client; restore existing session and profile. |
| `auth-entry.connect` | Obtain API JWT, bootstrap profile and schedule refresh; memory-only API token. |
| `auth-entry.openAccountControls` | Sign-in/signup/sign-out UI, validation, safe status text and role display. |

See AUTHENTICATION.md for trust boundaries and limitations. New accounts are viewers; no browser-controlled role promotion exists.

### Profile and account recovery

`/#account` opens the dedicated account page. Signed-out visitors choose Sign in or Create account; signed-in visitors see email/role, password change, membership status and sign-out. `POST /api/identity/change-password` forwards only to the configured managed provider, requires the application Origin and identity cookie, and lets the provider validate the current password. The browser sends `currentPassword`, `newPassword` and `revokeOtherSessions: true`; matching confirmation and a 12-character minimum are checked before sending. A failed password change does not show success. Membership upgrades use the existing authenticated Stripe billing portal; new subscribers use server-owned prices. No payment action is enabled while Stripe is unconfigured.
