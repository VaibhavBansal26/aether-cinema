# Free Render demo deployment

Source: https://github.com/VaibhavBansal26/aether-cinema (public). The existing Sites preview remains owner-private. Public source visibility does not publish the app or its backend.

`render.yaml` defines two free web services (Java API and Python recommendations), a free Key Value cache and one free PostgreSQL database, all in Oregon. Every compute/data plan is explicitly `free`. No paid worker, private service, disk or scaling plan is selected. The scheduled activity worker runs inside the API while it is awake; it is not guaranteed to keep draining during sleep.

## Setup sequence

1. Sign in to Render. Select New → Blueprint, choose the public repository URL and review `render.yaml`. Confirm all four plans are Free. If a free database already exists or capacity/quotas are unavailable, do not accept a paid replacement.
2. Supply a real trusted OIDC issuer, JWKS URL, audience and the eventual frontend origin. The local fixture signer must never be deployed. Supply `RECOMMENDER_URL` as the Python service's actual public HTTPS URL once assigned. Free web services cannot receive private-network requests, so the Python inference endpoint uses HTTPS plus the generated shared Bearer secret. The shared secret is generated once in an environment group and supplied to both services; never commit it.
3. Deploy resources. `/health` verifies that Java is alive, not that the schema/onboarding is complete. PostgreSQL credentials and cache location come from Render references. The separate `render-demo` Spring profile constructs a JDBC URL, bounds the pool to three connections and exposes health only. It uses encrypted DB connections (`sslmode=require`); use the TLS-verifying production profile for a production rollout.
4. Before directing users to the API, apply `server/schema.sql` and migrations 002, 003 and 004 in order with a trusted external migration job (`psql -v ON_ERROR_STOP=1 --single-transaction ...`). The free service does not support a paid pre-deploy job or interactive shell; migrations are not secretly run at application startup. Render's initial demo database user is the owner: after migration create a least-privilege application role and update API credentials before any real-user use.
5. Provision trusted users/profiles and their roles from your OIDC provider. Configure same-origin `/api` routing and a real frontend OIDC bootstrap. These are required for a signed-in experience; the current static Sites preview does not become authenticated just because a backend is running.
6. Verify `/actuator/health/readiness`, JWT rejection without credentials, own-profile progress/reactions and ML recommendations. Keep LLM policy disabled. Readiness includes DB connectivity; inspect the schema with authenticated catalog requests as well. Repeat the included small smoke test on an authorized staging target.
7. Stripe and Cloudflare are intentionally unconfigured in the demo template. Their placeholder values grant no provider access. Replace placeholders via Render secrets only when you have real accounts. Configure test-mode Stripe and provider media before testing those flows. Cloudflare Stream storage/delivery is billed separately and is not made free by Render.

## Boundaries and cost controls

Render free web services sleep after 15 minutes idle and share 750 running hours per workspace/month. Two continuously awake services would exhaust that shared allowance. Cold-start recommendations can fall back while Python wakes; a four-second inference timeout is intentional. Do not add artificial keepalive traffic to defeat the free tier. Free PostgreSQL expires after 30 days and is limited to one database per workspace; export demonstration data before expiry. Free cache is expendable and may reset. Use provider budget controls and do not add a paid plan to bypass limits. Confirm current limits in the dashboard before deployment.

This template is suitable for a small temporary demonstration, not high availability, million-viewer capacity, live billing or guaranteed background processing. A Render sign-in plus valid OIDC configuration are required before we can complete deployment. No public backend URL is claimed until the dashboard reports successful deployments and authenticated smoke checks pass.

References: [Render free services](https://render.com/docs/free), [Blueprint specification](https://render.com/docs/blueprint-spec).
