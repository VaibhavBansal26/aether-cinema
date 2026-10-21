# Deployment and production acceptance

The deployed Sites artifact serves only `dist/`. The reference Spring Boot service and schema are source artifacts and are not deployed with the static site. No Stripe, OIDC, database, or Cloudflare media credentials were available. External providers may charge; no paid service was purchased.

## Provisioning order

1. Restore GitHub authentication, create a private repository and enable protected branches, dependency scanning and CI.
2. Provision isolated staging PostgreSQL with backups/PITR, least-privilege roles, private/TLS connectivity, apply schema through a migration role. Do not use the migration role in the API.
3. Configure OIDC and trusted onboarding for users/profiles; provision Stripe customers idempotently. Never accept user IDs/roles/customer IDs from client input as authority.
4. Create Stripe test products/prices, secret key and signed webhook endpoint. Set server-known origin and prices. Test signup, renewals, dunning, cancellations, refunds, retries, duplicate/out-of-order events, and portal ownership. Access follows reconciled webhook state, never the Checkout success redirect.
5. Provision Stream, mark all paid media as requiring signed URLs, obtain approved content, captions and rights. Restrict media origins where supported. Test token expiry and renewal during long films; the current API token expires after 15 minutes.
6. Build `server/Dockerfile` with Java 21 and deploy the Spring Boot JAR/container behind HTTPS/WAF using same-origin routing. Inject JDBC credentials and provider secrets. Configure distributed rate limiting and request/connection timeouts at the gateway; in-memory per-process rate limiting is not included. API body limits are 32 KiB (1 MiB for Stripe). Provider calls have bounded connection/read timeouts. Use `/actuator/health/liveness` and `/actuator/health/readiness` probes; readiness includes PostgreSQL and must be restricted to infrastructure networks. Add structured metrics and alerts. Bundle/pin the client HLS player and run vulnerability review.
7. Connect frontend OIDC, catalog, reactions, progress reads/writes, Checkout, portal and HLS. Remove device-only admin preview and protect real admin screens with server role checks. Implement the missing publication pipeline.
8. Add entitlement/session limits, accessibility, parental controls, regional rights, privacy retention/deletion, DRM if required, monitoring and operational runbooks before public launch.

## Load acceptance (not executed)

Start with a provider-approved synthetic cohort and a staging environment. Separate media CDN load from API load; never generate uncontrolled production load. Ramp 1k → 10k → 100k → 1M only after each stage meets defined goals. Test reconnect storms, token refresh, new releases, webhook bursts, regional failures, database failover and cache eviction. Illustrative SLO candidates: playback startup p95 <3s on an agreed network; rebuffer ratio <0.5%; API p95 <250ms; monthly playback authorization availability 99.95%. Agree final targets and budgets before testing.

One million concurrent streams cannot be certified from static code review, small unit/integration suites, or a hosting URL. Require CDN capacity commitment, measured application capacity, billing/security review, restore drills and incident ownership.

## Rollback

Frontend: redeploy the last known-good saved Sites version. API: retain immutable release images and backwards-compatible schemas, roll back traffic gradually. Never delete payment events to force replay. Replay failed events through an audited operation with deduplication intact. Restore database only after identifying the recovery point and isolating writes; rehearse PITR.

## Spring Boot build and migrations

Maven and Java 21 are required locally; Docker supplies them when unavailable. `mvn verify` runs security/validation unit tests. Set `TEST_DATABASE_URL=jdbc:postgresql://…/aether_test` only for an empty disposable database owned by test user `aether_test` with test password `aether_test` to run PostgreSQL tests. Those tests initialize schema and truncate test tables. Never point this variable at a real environment. The GitHub Actions workflow supplies an isolated PostgreSQL service automatically.

The application intentionally disables automatic schema creation (`spring.sql.init.mode=never`). Keep applying the baseline using a migration role; do not apply `schema.sql` twice to existing databases. The Java migration changes no production schema. Before live launch adopt versioned Flyway/Liquibase migrations and an audited deployment migration job. No Flyway auto-migration is currently enabled.

## Recommendation rollout

Apply migration `002_recommendations.sql` once using a migration role. Configure each title's `kind` and editorial score, provision the private Python service, share a newly generated internal credential with Spring Boot, and wire authenticated frontend sessions/UUID mappings. Do not expose the internal credential or Python inference endpoint publicly. Begin with ML-only policy (the seeded default). The admin panel can opt into a trusted local Ollama model later; saving policy does not install/download a model. Review model quality and serving capacity before broad rollout. Detailed operation and opt-in/disable behavior: `RECOMMENDATIONS.md`.

## Infrastructure release

Apply `003_infrastructure.sql` after 002 before upgrading the API. Configure Redis and at least one activity worker; keep the seeded LLM policy disabled. The new `application-production.yml`, `infra/` configurations and `load-tests/api.js` require environment-specific networking, secrets and staging deployment. See `INFRASTRUCTURE.md` for complete migration, monitoring, retry/dead-letter and failure-drill procedures.
