# Aether Spring Boot API

Java 21, Spring Boot 3.5.16, Spring MVC, Spring Security OAuth2 Resource Server, Spring JDBC/HikariCP, Bean Validation, PostgreSQL, Stripe Java SDK and Actuator. Maven builds an executable JAR. The frontend is independent and still uses device-local sample data.

## Build and run

```sh
mvn -B -ntp verify
java -jar target/aether-api-0.2.0.jar
```

Run from this `server/` directory. The JAR requires the environment variables in `.env.example`. The application does not read a `.env` file by itself. Use a secret manager, environment injection or your container runtime. `DATABASE_URL` is a JDBC URL and DB username/password are separate variables. Apply `schema.sql` once using a migration role; schema initialization is disabled in the app. Provision OIDC users, profiles and Stripe customer mappings through a trusted onboarding process.

Container:

```sh
docker build -t aether-api:0.2.0 .
docker run --rm --env-file .env -p 8080:8080 aether-api:0.2.0
```

Existing Sites hosting serves the static frontend only; deploy this JVM container to a separate container/JVM host and use an HTTPS gateway for `/api`. Configuration includes graceful shutdown, a 20-connection Hikari pool, database statement timeout and bounded provider calls. Runtime image uses a non-root user.

## Testing

`mvn verify` runs the API security, validation, ownership, audience and webhook-forgery tests. Optional real PostgreSQL tests require `TEST_DATABASE_URL` for a new empty **disposable** database with username/password `aether_test` / `aether_test`. Tests create schema and truncate their own tables; never use a real database. CI provides this service automatically. Production schema credentials must be different.

Example test command (after provisioning the disposable database):

```sh
TEST_DATABASE_URL=jdbc:postgresql://localhost:5432/aether_test mvn -B -ntp verify
```

PostgreSQL tests cover progress upserts, cross-account write rejection, compound cursor pagination with equal publication timestamps, and draft/audit rollback. These do not certify provider integration, capacity or public launch readiness.

## Endpoints and safety boundaries

See [function/endpoint reference](../docs/REFERENCE.md). Roles come from PostgreSQL, never client claims. Profile ownership is checked before queries. Parameterized SQL is used throughout. Only Stripe's raw signed webhook bypasses OIDC. Webhook events reconcile current subscription state under a PostgreSQL transaction lock and dedupe by event ID. The API does not trust a Checkout success redirect as proof of payment.

Live secrets, authenticated frontend wiring, production identity onboarding, distributed gateway rate limiting, HLS player integration, upload/publication workflow, Checkout idempotency/duplicate-subscription handling and load testing remain required. Switching to Spring Boot alone does not make million-viewer capacity a verified property.

Version sources: https://docs.spring.io/spring-boot/3.5/system-requirements.html and https://github.com/stripe/stripe-java . Dependencies are pinned through the Boot parent/explicit Stripe version; generate an SBOM and run the organization's dependency policy before live deployment.

## Python recommendation integration

Apply `migrations/002_recommendations.sql` once after the baseline. Set optional `RECOMMENDER_URL` and `RECOMMENDER_SERVICE_TOKEN` to connect the private Python ML service. The recommendation API checks profile ownership and returns a safe editorial fallback if inference is unavailable. Admin LLM policy is persisted, audited, revision-checked and seeded OFF. Local Ollama is supported only after explicit admin model/provider enablement; no OpenAI key is required. See [recommendation guide](../docs/RECOMMENDATIONS.md).

## Queue, Redis and production runtime

Apply `migrations/003_infrastructure.sql` after 002 before running this release. Progress/reactions now atomically enqueue activity jobs. Set `WORKER_ENABLED=true` on worker replicas to drain them. Set `RECOMMENDATION_CACHE_ENABLED=true` and `REDIS_URL` to cache ML responses. Activate `production` for TLS-verified database connections and the private management port. See [infrastructure guide](../docs/INFRASTRUCTURE.md) for startup, retries, dead letters, monitoring and staged tests.

## Admin video publishing

Apply `004_video_publishing.sql` after 003. The new `/api/admin/videos` endpoints reserve direct tus uploads, verify provider processing, create protected adaptive previews and publish reviewed videos. See [video workflow and limitations](../docs/VIDEO-PUBLISHING.md). The API never receives original video bytes.
