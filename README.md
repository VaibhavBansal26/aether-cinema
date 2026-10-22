# AETHER — Cinema, considered

A cinematic streaming product foundation with an ink-black theme, lime accents, original promotional key art, and content-first browsing. **This release includes a free Render demonstration with managed Neon authentication and a separate owner-private Sites preview. It is not a production service and has not been tested at one million concurrent viewers.**

Live demo: https://aether-api-demo.onrender.com · [Authentication](docs/AUTHENTICATION.md) · [Deployment limits](docs/FREE-DEPLOYMENT.md).

## Run

Frontend: `npm run dev` then open http://localhost:4173. Tests: `npm test` (Node 22+). Prebuilt assets are included. Run `npm ci && npm run build:auth` to rebuild the managed sign-in client.

API: Java 21 + Spring Boot 3.5.16 + Maven. In `server/`, run `mvn verify`, supply the environment variables in `.env.example` through your secret manager, apply `schema.sql` using a migration role against a new PostgreSQL database, provision OIDC users/profiles and Stripe customer mappings, then run `java -jar target/aether-api-0.2.0.jar`. The database URL must be JDBC format (`jdbc:postgresql://…`); credentials use separate `DATABASE_USERNAME` / `DATABASE_PASSWORD` variables. Spring does not automatically load `.env` files. No real service credentials are committed; isolated local tests use clearly marked disposable fixtures. The combined cloud deployment supports managed sign-in and account bootstrap. The disconnected Sites preview keeps device-local state. Real catalog/media, billing credentials and production hardening remain required before launch.

Container build: `docker build -t aether-api:0.2.0 server`. Run with your injected environment or a protected local env file using `docker run --rm --env-file server/.env -p 8080:8080 aether-api:0.2.0`. The image runs as a non-root user. See [backend setup](server/README.md).

## What works in the preview

- Responsive catalog with genre filters, title search, Movies and Series views, and a sticky navigation bar.
- Full-bleed cinematic hero with three selectable featured titles.
- Horizontal handpicked, originals, editorial top 10, series and nature shelves; genre discovery tiles; rich title-detail dialogs.
- Editorial film details and a personalized ranking based on liked genres.
- Saved collection, likes and continue-watching on one browser/device.
- Public sample playback with native controls, saved position, volume, mute and playback rate.
- Network-aware initial quality recommendation. This is displayed guidance; the sample MP4 does not switch renditions.
- Membership comparison with explicit billing-unavailable messaging.
- Creator studio with device-local draft preview, protected recommendation settings, and the admin video-publishing workflow. Live uploads and publishing require the deployed API, an admin session and Cloudflare.
- Native dialogs, keyboard focus, accessible control labels, reduced loading through lazy imagery.

## Python recommendation engine

`recommender/` implements TF-IDF content similarity, watch/like/format affinity, optional offline implicit-feedback SVD, diverse ranking and explicit homepage row/card positions. Spring Boot reads owned-profile history and calls the private service. **LLM is optional and OFF by default.** Only an authenticated admin can enable Local Ollama and specify an installed model in Creator studio → Manage recommendation settings. No OpenAI API key is needed or used. See [recommendation setup](docs/RECOMMENDATIONS.md).

Apply `server/migrations/002_recommendations.sql` once after the baseline schema. The static preview has no live account/backend connection and therefore cannot enable an LLM; its settings form clearly shows that boundary. Python and Java integration were verified locally; both services are also deployed on the free Render demo.

## Integration foundation

The separate Spring Boot API uses Spring Security OAuth2 Resource Server for JWT verification (issuer, audience, expiry and JWKS signature), application-owned roles, profile ownership, Spring JDBC parameterized writes, HikariCP connection pooling, Bean Validation, signed Cloudflare playback authorization, the official Stripe Java SDK, raw-body webhook signatures, event deduplication and per-subscription serialization, bounded payloads, graceful shutdown and transactional draft audit records. Actuator exposes anonymous health probes and authenticated Prometheus metrics; the free Render profile exposes health only. Configure distributed rate limiting at the gateway/WAF before exposure.

See [architecture](docs/ARCHITECTURE.md), [feature and function reference](docs/REFERENCE.md), [operations](docs/OPERATIONS.md), and [validation](docs/VALIDATION.md). All authored behavior entry points have source comments. External library internals are documented by their vendors.

## GitHub

Public source repository: [VaibhavBansal26/aether-cinema](https://github.com/VaibhavBansal26/aether-cinema).

```sh
git clone https://github.com/VaibhavBansal26/aether-cinema.git
cd aether-cinema
```

The Sites preview has a separate source remote and remains owner-private. A free-tier Render demo template is included in `render.yaml`; see [deployment setup and limits](docs/FREE-DEPLOYMENT.md). Public backend deployment still requires Render access and trusted OIDC configuration.

## Media and screenshots

Film names/synopses are fictional demo content. Landscape imagery is remotely loaded from Unsplash; obtain final production asset rights and self-host approved imagery. All play buttons use the same Big Buck Bunny demonstration clip (Blender Foundation, CC BY 3.0), visibly labelled as a sample. No catalog feature films are hosted.

The frontend redesign was visually checked in Chrome at desktop, 768px tablet, 390px and 360px mobile widths. Screenshots and the design review are under `docs/screenshots/` and `docs/FRONTEND-REDESIGN.md`. The in-app browser was unavailable, so the connected Chrome browser was used. Five original concept art assets were created with the built-in ImageGen tool and optimized to JPEG; prompts are in `docs/ARTWORK.md`. Lucide icons are bundled with their license in `dist/assets/`.

Infrastructure additions: see [queue, Redis cache, production database, monitoring and staged tests](docs/INFRASTRUCTURE.md). Apply migration 003 before running the updated backend. LLM stays disabled by default.

Admin video publishing is available under **Creator studio → Open video publishing studio**. See [direct resumable uploads, encoding, adaptive preview and publishing](docs/VIDEO-PUBLISHING.md). Apply migration 004 and configure Cloudflare before using live uploads.

A complete disposable local backend stack is available in [LOCAL-STACK.md](docs/LOCAL-STACK.md). It includes a test-only JWT signer and must never be publicly deployed.

Managed sign-in, account bootstrap and role boundaries are documented in [AUTHENTICATION.md](docs/AUTHENTICATION.md). The combined frontend/API image is built with `docker build -f deploy/Dockerfile .`.

## Scaling controls and private R2 budgets

Implemented: account-wide playback leases and stream limits, distributed Redis request budgets, bounded API/inference admission, revision-based recommendation caching before history loading, indexed profile-history queries, durable Kafka outbox/inbox consumers, poison-record dead letters, real movie/episode catalog integration, Stripe checkout intents, and lazy HLS adaptive playback. See [Kafka and scaling](docs/KAFKA-AND-SCALE.md).

[Private R2 cost controls](docs/R2-BUDGETS.md) reserve no more than 8 GB and stop Class A/B operations at conservative ceilings. Admin usage endpoints track UTC cycle dates, automatic operation renewal and a rolling safety window; storage does not reset monthly. `tools/encode_hls.py` prepares local GB originals for the private gateway. Cloudflare account connection is a separate deployment step; local emulator tests do not prove account-wide billing protection. The paid Stream adapter is disabled by default.
