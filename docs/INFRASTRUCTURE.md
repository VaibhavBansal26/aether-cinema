# Infrastructure additions

## Implemented responsibilities

The Spring API remains the control plane. Video bytes belong on Cloudflare Stream or another video CDN; none pass through the API, Redis or PostgreSQL. These additions do not establish one-million-viewer capacity.

`RecommendationCache` stores successful ML layouts in Redis for 60 seconds. The SHA-256 key includes the profile, complete inference inputs, and admin policy revision. Ownership is checked before lookup. Changed progress, reactions, catalog metadata and policy produce new keys; retired entries expire. Failures fall back to the private Python service and then editorial recommendations. Database reads still occur on cache hits. No cross-replica single-flight protection yet; burst requests can duplicate inference. Model deployment changes must bump the key version or clear this prefix.

`LibraryService` writes progress/reactions and a durable `activity_jobs` record in the same PostgreSQL transaction. Playback remains immediately readable. `ActivityWorker` updates `profile_activity` and marks completion in one transaction. PostgreSQL `FOR UPDATE SKIP LOCKED` distributes jobs across replicas. A crashed connection rolls back claims and effects. Failed effects roll back to a savepoint, retry with exponential delay, then enter `dead` after five attempts. No network calls occur under the row lock. This is a PostgreSQL-backed durable queue; Redis is deliberately an expendable cache. The current worker builds activity aggregates, not video transcodes or model training.

Enable `WORKER_ENABLED=true` on at least one backend deployment; leave false on API-only replicas. Workers currently share the API executable and therefore its configuration requirements. Each poll processes up to 50 jobs, one transaction each; tune by measured database capacity. Delivery durability depends on PostgreSQL backups, synchronous replication policy and storage guarantees. Effects outside the database would need an idempotency key and separate delivery adapter.

## Local startup and migrations

1. Set a disposable `LOCAL_DB_PASSWORD`, then `docker compose -f infra/compose.yml up -d`.
2. Apply `server/schema.sql`, then `002_recommendations.sql` and `003_infrastructure.sql` in order with `psql -v ON_ERROR_STOP=1`. Existing databases apply only missing migrations. Startup never auto-migrates.
3. Configure the existing OIDC, Stripe, Cloudflare and private recommender variables, plus `REDIS_URL`, `RECOMMENDATION_CACHE_ENABLED=true`, `WORKER_ENABLED=true`.
4. Build/run the Java API using the server README. Start Python with `compose.recommendations.yml` or its Dockerfile. The local infrastructure Compose runs only PostgreSQL and Redis.

## Production database and security

Activate `production`: TLS certificate verification, bounded Hikari pool (10 by default), short acquisition/statement/socket timeouts, bounded connection lifetime and TCP keepalive. Supply a trusted database CA and DNS hostname. Use managed HA PostgreSQL with PITR, tested restores, encryption and a separate migration owner. The application role needs only table SELECT/INSERT/UPDATE/DELETE and sequence USAGE; no DDL or superuser. Keep total API+worker pools below database connection capacity. With a transaction pooler, verify JDBC prepared statement compatibility (e.g. `prepareThreshold=0` where required) and session settings in staging. Index queue pending claims and catalog/profile lookups; monitor execution plans, vacuum, WAL and table growth. Redis requires private networking, ACL credentials and TLS (`rediss://`); local Compose is not a production HA setup.

## Observability and operations

Actuator exposes Prometheus metrics authenticated by JWT. Production uses management port 9090: firewall this port to monitoring only. `infra/prometheus.yml` is a scrape configuration template, requiring a mounted valid JWT, automatic credential rotation, reachable `api:9090` DNS, and `alerts.yml`. It does not provision a monitoring service. Standard request latency/status, JVM and connection-pool metrics accompany cache hit/miss/error and worker completed/retried/poll-failure counters. Counters record attempts and can precede a transaction commit; use database counts for authoritative completions. Health readiness depends on DB; Redis remains optional. No per-profile metric labels or raw history logs.

Useful queue checks:

```sql
SELECT state,count(*),min(created_at) FROM activity_jobs GROUP BY state;
SELECT count(*) FROM activity_jobs WHERE state='pending' AND created_at < now()-interval '5 minutes';
```

Alert on dead jobs and pending age as well as the provided HTTP/poll alerts. Investigate and fix the cause before replaying a dead job: `UPDATE activity_jobs SET state='pending', attempts=0, available_at=now() WHERE id = <reviewed_id> AND state='dead'`. Delete completed jobs older than seven days in small batches using the cleanup index; retain dead jobs for investigation. Schedule cleanup and SQL-derived backlog metrics with your production operator; they are not installed here.

## Staged load and failure verification

`load-tests/api.js` provides k6 smoke (2 users), baseline (10), and opt-in stress (100) stages. Supply `BASE_URL`, `TOKEN`, `PROFILE_ID`; use `STAGE=stress ALLOW_STRESS=yes` only on a provisioned staging target. Thresholds fail on >1% request failures, p95 >1s or >1% failed checks. This tests catalog/recommendation APIs, not paid CDN traffic, video QoE or million concurrent viewers. Tokens must remain valid throughout a run.

Failure stages, on disposable staging resources only:

1. Run smoke with LLM disabled. Record p95/p99, cache hit ratio, DB pool saturation and worker lag.
2. Stop Redis; repeat smoke. Expect recommendations with bounded cache timeout, increasing cache errors, no lost writes. Restart Redis.
3. Stop Python; repeat smoke. Expect editorial fallback. Restore Python.
4. Disable all workers, write progress, confirm playback resumes immediately and queue grows. Restart workers; confirm backlog drains and aggregates match completed jobs.
5. Kill a worker mid-transaction. Confirm uncommitted jobs return pending and no duplicate aggregate effect.
6. Inject a failing aggregate constraint in the disposable database. Verify retry delay, fifth-attempt dead letter, no partial effects. Remove fault before replay.
7. Repeat baseline then stress only after each preceding stage passes. Test region/DB failover and CDN cold-cache bursts with provider support before any large launch.

Automated PostgreSQL tests cover transaction rollback, atomic completion, and injected failure/dead-letter behavior. Cache unit tests cover failure bypass and policy/profile key separation. A separate real Redis integration test passed, verifying reads, writes and the 60-second expiration. A later disposable local-stack verification passed the 2-user/20-second k6 smoke, Redis-stop and Python-stop drills. See LOCAL-STACK.md and VALIDATION.md. Larger authenticated stages, process-kill and regional failover still require staging verification.
