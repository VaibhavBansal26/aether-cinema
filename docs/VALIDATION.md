# Validation record

Date: October 21, 2026.

Passed for Spring Boot migration: Maven `verify` built the executable Java 21 / Spring Boot 3.5.16 JAR and ran **17 backend tests with zero failures/errors/skips**. Verification used the official Maven/Temurin 21 image and an isolated PostgreSQL 16 container.

- Eight HTTP/filter-chain tests: public liveness, unauthenticated catalog/admin rejection, invalid timeline and missing reaction fields, oversized request rejection, no-store playback response, and webhook signature-verification boundary.
- Three authorization tests: audience validation, server-owned admin role enforcement and cross-account profile protection.
- Two webhook tests: forged/missing signatures cannot touch subscription state.
- Four real PostgreSQL tests: upserted progress and sound settings, cross-account reaction rejection, compound pagination with equal timestamps, and transactional draft/audit rollback.

Runtime smoke check: started the actual built JAR with test-only configuration; `/actuator/health/readiness` returned HTTP 200 with `{"status":"UP"}`; unauthenticated `/api/catalog` returned HTTP 401. No real provider was contacted. The runtime smoke container and test database are removed after verification.

Previous frontend verification: three Node unit tests passed for low bandwidth/save-data, resume bounds, cold-start/liked-genre ranking and nonmutation. Frontend source was not changed by the Spring Boot migration; it was subsequently redesigned and the three pure-algorithm tests passed again.

Still not verified: actual ABR, live OIDC/JWKS integration (the HTTP tests use a test JWT), Stripe/Cloudflare provider integration, protected media, production security, accessibility audit, capacity, disaster recovery. Unit tests reject forged webhook signatures; valid real Stripe events and provider reconciliation are not end-to-end tested.

The earlier in-app browser timeout was resolved for frontend verification by using the available connected Chrome browser. The redesigned interface was captured at desktop width, tablet (768×1024), mobile (390×844) and small mobile (360×800). No horizontal page overflow was observed at those sizes. Loaded-image checks returned no broken images.

Browser checks passed: cinematic feature switching (including long-title spacing); Movies and Series views; Drama filter; exact title search and empty-state reset; like state; collection add and My list; Originals view-all; membership comparison and unavailable-checkout status; studio draft save; real sample video loading/playback; saved resume at ~3.03 seconds; and Continue watching. Only local QA data was created, and it was cleared through the profile controls afterward. The page's broader accessibility compliance has not been audited.

The original Google-hosted sample returned HTTP 403. It was replaced with the W3C-hosted Big Buck Bunny trailer at https://media.w3.org/2010/05/bunny/trailer.mp4 (HTTP 200, native player duration ~33 seconds, readyState 4, no media error). This is sample playback only, not a feature-film service.

Screenshots and review: `docs/FRONTEND-REDESIGN.md` and `docs/screenshots/`.

Manual acceptance: save/unsave, reload and verify collection; like genre and verify ranking; search and test empty state; select Films/Series; play sample, pause after 10s, reopen and verify resume; change volume/mute, reopen and verify settings; save admin draft and verify explicit local-only status; open plans and verify no false payment success; keyboard navigate all dialogs; test 360px/768px/1440px viewports.

Artifacts: `server/target/aether-api-0.2.0.jar` (also delivered as `AETHER-api-0.2.0.jar`), `server/Dockerfile`, Java source/tests and `.github/workflows/verify.yml`. The frontend Sites publication is static; Java hosting is not provisioned.

## Recommendation engine and optional LLM change

Final checks: **15 Python tests passed**, **25 Java tests passed** (zero failures/errors/skips when the real Python test service was supplied), and **3 frontend core tests passed**. The Java suite includes a live internal HTTP call through `RecommendationGateway` to the FastAPI service using actual PostgreSQL catalog/profile records. It returned `model=tfidf-v1`, `personalized=true`, `llm_status=disabled`, and a real Continue watching row. The Java JAR and non-root Python container built successfully.

Python checks include watched-genre ranking changes, series shelf preference, one-based row/card positions, cold start, completion exclusion, actual unfinished playback, safe SVD training/loading with synthetic data, authenticated private inference and bounded/validated input. A fail-on-call transport proved zero inference requests when LLM policy is disabled even with provider/model specified. Enabled-provider behavior was exercised with mocked HTTP success, invented/duplicate/missing IDs, and timeouts. No real LLM was started, contacted or downloaded.

Java checks include unauthenticated settings rejection, provider/model validation, viewer/admin separation, profile ownership, default-off persistence, transactional policy/audit changes, stale revision conflict after disable, and disabled-policy JVM→database→Python integration.

Browser check: Creator studio → Manage recommendation settings displayed the protected admin policy form. With no production session, the enable toggle/model/save controls remained disabled; there was no fake local enablement. Screenshot: `screenshots/10-recommendation-settings.png`. The authenticated UI save path was not exercised against a public OIDC deployment; server-side authorization/persistence was tested separately.

The synthetic example is in `recommendation-example.json`. No production training dataset or quality metric is claimed. Public Java/Python hosting, OIDC onboarding, production catalog/artwork mapping, scheduled model jobs and large-scale load testing remain pending.

## Infrastructure validation — October 21, 2026

Maven verify passed 31 Java tests with zero failures/errors/skips against disposable PostgreSQL 16 and the Python ML service. Added tests verify atomic job acknowledgement/effect, rollback of user writes and outbox events, five-attempt dead lettering under an injected database constraint failure, cache-disabled behavior, cache failures and profile/policy cache-key isolation. A separate real Redis integration test passed (1 test, zero failures/errors/skips), verifying cache reads, writes and bounded TTL. k6 stages and process-kill/region-failover drills have not run against a deployed authenticated backend. No million-user capacity claim is supported.

## Video publishing and end-to-end stack — October 21, 2026

All **44 Java tests passed with zero failures/errors/skips** against disposable PostgreSQL, Redis and Python. Five Node frontend tests passed. The new provider contract tests reserve a mocked 5 GB upload using metadata only; they do not transfer that file. A PostgreSQL test verifies upload → processing → publication and its audit record using mocked provider responses. Admin HTTP tests enforce authentication, bounded uploads and required reviews.

The complete local stack passed authenticated HTTP checks for ownership, admin isolation, progress/sound restoration, reactions, recommendations, video listing and unconfigured upload rejection. Separate Redis-stop and Python-stop drills passed; Python outage returned editorial fallback. A 2-user/20-second k6 smoke completed with thresholds passing; logs accompany the deliverable. This is a small local API test, not CDN, GB-video, failover or million-viewer validation. No real Cloudflare upload or live Stripe charge was run. Frontend video-studio screenshots show the honest disconnected/disabled state.

## Managed authentication update

All 52 Java tests passed with zero failures/errors/skips against disposable PostgreSQL, Redis and Python. Six frontend tests passed; the combined Docker image built successfully. Real Neon managed sign-up and JWT issuance succeeded with a synthetic fixture account. Cloud-backed Java onboarding results are recorded separately in the authentication check report. No owner/admin privilege was granted to the synthetic fixture.

The same-origin identity proxy passed request-origin, cookie isolation, secure cookie rewriting and forwarding tests. A local real-provider check returned successful sign-in, session restoration and JWT issuance through the proxy. Public browser checks passed on the final deployment: viewer session restored after reload and sign-out succeeded.

Public HTTPS API verification passed: auth configuration, real managed sign-in/cookie session/token, idempotent viewer bootstrap, unauthenticated 401, viewer/admin 403, DB readiness and confirmed sign-out. The warm Python service returned `model=tfidf-v1`, `llm_status=disabled`; a cold request returned the documented editorial fallback. The cloud catalog contains no published films, so this does not validate ranking quality or real media playback.

Browser proof: `screenshots/18-cloud-session-restored.png` shows the restored Viewer account with empty credential fields after page reload on the public HTTPS deployment.

## Distributed controls and private R2 gateway — October 22, 2026 UTC

The full backend suite passed 67 tests with zero failures, errors or skips using real disposable PostgreSQL, Redis and Python. Six frontend tests and seven gateway tests passed. The gateway tests use the real local workerd Durable Object/SQLite and R2 emulator. A targeted backend rerun passed after adding the Cloudflare-compatible client identification.

Two local API replicas with real Kafka completed 1,454 staged requests with zero HTTP failures and 53.72 ms p95. The overload test exercised rejection/backpressure; its rejected request count is not successful throughput. Broker, Redis and Python outage checks passed. A real malformed Kafka record reached `aether.activity.v1.DLT`; duplicate event delivery did not change the existing projection count. Query microbenchmark details and production gates are in `KAFKA-AND-SCALE.md`.

The private Cloudflare gateway successfully reserved and uploaded a synthetic four-asset, 592,684-byte HLS bundle, completed it, and returned its signed playlist. The durable ledger recorded four Class A and one Class B operations. This validates a small live storage path, not GB transfers or a million concurrent viewers. The billing day is unverified and explicitly reported as `calendar-estimate`; rolling 35-day guardrails remain active. See `R2-BUDGETS.md` for limits and account-wide billing caveats. Cloud PostgreSQL migrations through 009 were applied before the application release.

Application release `c61f1b3` deployed successfully on Render. Public HTTPS checks passed: managed sign-in, secure cookie session/token, idempotent viewer bootstrap, recommendation model `tfidf-v1` with `llm_status=disabled`, billing status, readiness, sign-out, and viewer rejection (403) at storage limits/cycles endpoints. New catalog and R2 studio assets returned 200. Initial rate-limited API calls returned 503 during a cold Redis connection; subsequent checks passed with admission controls still enabled. The current health probe can become healthy before Redis is ready; operators should use dependency-aware readiness before production. No test account was granted admin rights.

Deployment proof: `screenshots/20-r2-release-live.png`.

## Profile and authentication recovery — October 21, 2026 EDT

Seven frontend tests and fourteen targeted Java authorization, identity-proxy and admission checks passed. A local Redis-stop check returned readiness 503, liveness 200 and protected API 503; after restart readiness and auth configuration returned 200. The deployment health check requires PostgreSQL and Redis. The free profile uses bounded 3-second Redis connection and 2-second command timeouts. Account-specific verification records and signed-in screenshots are retained locally rather than published. Successful password replacement and live Stripe upgrades have not been tested.
