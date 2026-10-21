# Architecture and scale plan

## Delivery path

Browser → edge-hosted frontend → Java 21 / Spring Boot authenticated API → PostgreSQL through JDBC/HikariCP. Spring Security validates OIDC JWTs; Bean Validation checks requests; Spring transactions cover draft/audit and webhook writes. Maven builds the executable JAR; a multi-stage Dockerfile produces a non-root runtime image. After entitlement validation the API returns a short-lived signed Cloudflare Stream HLS manifest; video segments travel directly from the video CDN to the viewer. Video must never pass through Spring Boot or PostgreSQL.

Cloudflare Stream provides upload, encoding and adaptive bitrate delivery: https://developers.cloudflare.com/stream/
Signed delivery: https://developers.cloudflare.com/stream/viewing-videos/securing-your-stream/
Custom HLS players: https://developers.cloudflare.com/stream/viewing-videos/using-own-player/
Stripe subscriptions: https://docs.stripe.com/billing/subscriptions/overview
Stripe webhooks: https://docs.stripe.com/billing/subscriptions/webhooks

## A million concurrent viewers is a capacity target, not a delivered guarantee

At a planning assumption of 4 Mbps/viewer, 1 million viewers imply 4 Tbps of delivered video and approximately 1.8 PB per hour. These are arithmetic estimates, not provider capacity commitments. At 30-second progress checkpoints the control plane sees about 33,333 writes/sec before jitter, retries and other requests. The reference API writes directly and is not suitable for that volume.

Before that target: negotiate CDN capacity and regional coverage; use origin shielding, signed-cookie/token caching rules, WAF and anti-abuse; partition progress events by profile, buffer them through a durable queue, compact and batch upserts; use Redis/edge caches for catalog and short-lived entitlement caching with revocation bounds. Avoid a single global database write on every segment request. Precompute recommendations offline and cache results by profile/version. Autoscale stateless APIs using observed p95/p99 latency and queue lag; keep connections bounded through PgBouncer or a managed pool.

PostgreSQL stores identity, subscriptions, metadata and compact playback state. Composite keys enforce one state row per profile/title. Partial indexes accelerate published catalog reads; a profile/time index serves recent playback. Separate high-volume event storage from transactional tables. The Spring API uses a compound (published_at,id) cursor to retain titles with equal publication timestamps. Add search indexes only after measured query plans. Route read-heavy catalog traffic to caches/read replicas with explicit staleness budgets.

## Adaptive streaming

1. Transcode a ladder of representations and aligned segments through Stream.
2. Use native HLS on supported clients or a pinned, bundled HLS player on others.
3. Start conservatively using save-data/device/network hints; hints are optional and not throughput truth.
4. Select a representation under approximately 70% of measured throughput using an EWMA; downgrade immediately on rebuffer/low buffer, upgrade only after sustained healthy buffer. Let the production player's tested ABR implementation drive actual switching.
5. Preserve media time and audio settings through quality changes. Track startup delay, rebuffer ratio, failures and bitrate per device/network.

`qualityFor` implements only step 3. The demo is a single MP4. Avoid claiming it is AI or actual ABR. A contextual-bandit or model-predictive policy can be evaluated later against a rules baseline using rebuffer rate, startup time, visual quality, bandwidth cost and privacy constraints. Ship a deterministic fallback and use staged experiments, never blindly infer internet speed from geography.

## Recommendations and publishing

The running preview scores each title using editorial rating, genre affinity from likes and meaningful watch progress, and a completion penalty. It has a deterministic cold start and exposes a recommendation reason. Production should combine content metadata embeddings, collaborative signals and editorial candidates, then rerank for diversity, freshness, language, age and rights availability. Do not rely on engagement alone. Collect consented events with retention limits; evaluate coverage, satisfaction, discovery and bias, not only watch time.

Publishing workflow: restricted direct upload → signed transcoder callback verification → quality/caption checks → moderation and territory/rights review → scheduling → atomic publish → cache invalidation → audit record. The provided API supports audited draft creation only. Uploads, callbacks, publishing transitions and scheduled recommendation feature/training jobs remain to be implemented.

## Suggested product improvements

Prioritize transparent recommendations and a discovery reset, data-saver mode, captions and audio descriptions, household profiles and parental controls, meaningful search, and a curated short-form discovery rail that leads into full-length viewing. These are product hypotheses, not claims of verified market trends. Use experiments to decide whether they improve this audience's experience. Add offline downloads only with appropriate DRM and rights. Consider AV1/HEVC only after validating device compatibility, transcoding cost and the selected CDN's current support.

## JVM deployment boundary

The existing Sites publication serves only the static frontend and does not execute Java. Deploy the Spring Boot container separately on a JVM/container host or Kubernetes, then route `/api` to it through the same origin. The code has not provisioned this host. Keep Hikari pool totals within database capacity across all replicas. JVM virtual threads, WebFlux, Kubernetes and Kafka are not presently implemented or required merely to change frameworks. Select them only from measured workloads; changing to Spring Boot does not establish million-viewer capacity.

## Implemented recommendation service

The Python/FastAPI service now provides TF-IDF content ranking, watch/format affinity, an optional offline SVD artifact, MMR diversity, and one-based homepage row/card positions. Spring Boot enforces ownership before supplying signals and reads persisted admin policy. LLM inference is off by default and does not occur in model indexing/training or fallback ranking. Optional Local Ollama reranking requires explicit admin provider/model/enablement. No OpenAI key or SDK is used. See `RECOMMENDATIONS.md` for exact contracts and deployment limitations.

## Implemented infrastructure update

Recommendation layouts now use an optional Redis response cache; activity writes atomically enqueue PostgreSQL jobs, with competing transactional workers updating profile aggregates. API progress remains synchronous and immediately readable. These are foundational components, not the high-throughput buffered playback ingestion architecture described as the scaling target above. Production Hikari/TLS configuration and Micrometer/Prometheus instrumentation are included. See `INFRASTRUCTURE.md` for exact guarantees and remaining capacity work.
