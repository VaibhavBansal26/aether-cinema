# AETHER — Cinema, considered

A cinematic streaming product foundation with an ink-black theme, lime accents, original promotional key art, and content-first browsing. **This release is a private functional frontend preview plus an unprovisioned reference API. It is not a production service and has not been tested at one million concurrent viewers.**

## Run

Frontend: `npm run dev` then open http://localhost:4173. Tests: `npm test` (Node 22+). No frontend dependencies or build step.

API: Java 21 + Spring Boot 3.5.16 + Maven. In `server/`, run `mvn verify`, supply the environment variables in `.env.example` through your secret manager, apply `schema.sql` using a migration role against a new PostgreSQL database, provision OIDC users/profiles and Stripe customer mappings, then run `java -jar target/aether-api-0.2.0.jar`. The database URL must be JDBC format (`jdbc:postgresql://…`); credentials use separate `DATABASE_USERNAME` / `DATABASE_PASSWORD` variables. Spring does not automatically load `.env` files. No real service credentials are committed; isolated local tests use clearly marked disposable fixtures. The static preview does not call this API; frontend authentication, HLS playback and remote persistence must be wired before launch.

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

Apply `server/migrations/002_recommendations.sql` once after the baseline schema. The static preview has no live account/backend connection and therefore cannot enable an LLM; its settings form clearly shows that boundary. Python and Java integration were verified locally; public service deployment remains pending.

## Integration foundation (not live)

The separate Spring Boot API uses Spring Security OAuth2 Resource Server for JWT verification (issuer, audience, expiry and JWKS signature), application-owned roles, profile ownership, Spring JDBC parameterized writes, HikariCP connection pooling, Bean Validation, signed Cloudflare playback authorization, the official Stripe Java SDK, raw-body webhook signatures, event deduplication and per-subscription serialization, bounded payloads, graceful shutdown and transactional draft audit records. Actuator exposes health probes only. Configure distributed rate limiting at the gateway/WAF before exposure.

See [architecture](docs/ARCHITECTURE.md), [feature and function reference](docs/REFERENCE.md), [operations](docs/OPERATIONS.md), and [validation](docs/VALIDATION.md). All authored behavior entry points have source comments. External library internals are documented by their vendors.

## GitHub

The connected GitHub tool set cannot create repositories and the installed `gh` session is expired. After `gh auth login`, create a private repository from this checkout:

```sh
gh repo create aether-cinema --private --source=. --remote=github --push
```

The Sites remote is separate and must be preserved. Repo creation was not completed by this release.

## Media and screenshots

Film names/synopses are fictional demo content. Landscape imagery is remotely loaded from Unsplash; obtain final production asset rights and self-host approved imagery. All play buttons use the same Big Buck Bunny demonstration clip (Blender Foundation, CC BY 3.0), visibly labelled as a sample. No catalog feature films are hosted.

The frontend redesign was visually checked in Chrome at desktop, 768px tablet, 390px and 360px mobile widths. Screenshots and the design review are under `docs/screenshots/` and `docs/FRONTEND-REDESIGN.md`. The in-app browser was unavailable, so the connected Chrome browser was used. Five original concept art assets were created with the built-in ImageGen tool and optimized to JPEG; prompts are in `docs/ARTWORK.md`. Lucide icons are bundled with their license in `dist/assets/`.

Infrastructure additions: see [queue, Redis cache, production database, monitoring and staged tests](docs/INFRASTRUCTURE.md). Apply migration 003 before running the updated backend. LLM stays disabled by default.

Admin video publishing is available under **Creator studio → Open video publishing studio**. See [direct resumable uploads, encoding, adaptive preview and publishing](docs/VIDEO-PUBLISHING.md). Apply migration 004 and configure Cloudflare before using live uploads.

A complete disposable local backend stack is available in [LOCAL-STACK.md](docs/LOCAL-STACK.md). It includes a test-only JWT signer and must never be publicly deployed.
