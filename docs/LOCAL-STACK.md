# Local end-to-end stack

`docker compose -f infra/compose.stack.yml up -d --build` launches PostgreSQL, Redis, private Python recommendations, the Spring API, a competing worker, and a disposable JWT signer. Port 8080 binds only to loopback. The database is temporary (tmpfs), and the signer produces local test identities. Never publish this stack or reuse its fixture credentials in cloud deployment.

The migration job applies baseline and 002–004 with advisory locking and a checksum journal. A changed applied migration fails rather than silently reapplying. Fixture seeding is idempotent. This runner is for the disposable stack, not automatic production migration adoption.

Run `docker compose -f infra/compose.stack.yml run --rm --no-deps verify` after startup to exercise real signed JWT validation, database identity/profile/admin checks, progress with sound settings, reactions, ML recommendations, video studio listing, and rejection of upload requests without configured Cloudflare. The seed has viewer, admin and other-account identities only. Their test signer is not exposed on a host port.

Failure checks (local resources only): stop `redis`, run verification with `CHECK_MODE=redis-down`, then restore it. Stop `recommender`, run with `CHECK_MODE=python-down`, then restore it. Both drills passed on October 21, 2026. Python outage returned editorial recommendations; Redis outage preserved ML and user writes. No LLM request was made.

The k6 smoke stage also ran against this disposable local stack: 2 virtual users for 20 seconds. See the saved smoke log and validation guide for measured results. This is not CDN or GB-video capacity verification. Higher stages and region/database failover are not executed.

Shutdown: `docker compose -f infra/compose.stack.yml down`. This destroys the temporary database contents. No public backend deployment is provisioned by this file.
