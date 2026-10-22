# R2 budget enforcement and cycle dates

**Prepared code; not an account-level billing guarantee.** The gateway must be deployed and configured before real R2 uploads work. It is independent of the paid Cloudflare Stream adapter, which is disabled by default. Never enable Stream to get around these caps.

## Limits

The gateway's fixed code ceilings are **8,000,000,000 bytes**, **800,000 Class A operations** and **8,000,000 Class B operations**. These deliberately leave headroom below the published 10 GB-month / 1M / 10M Standard allowance. All reservations share one account-wide Durable Object. API replicas, Redis loss and browser retries cannot mint another allowance. Every R2 binding PUT or GET requires a committed debit first, including retries and requests that later fail. There are no direct S3 presigns or public bucket URLs.

Storage is reserved for the entire encoded bundle before any asset can be uploaded. Each immutable asset is at most 16 MiB. Partial and abandoned reservations continue to count; storage never resets at month renewal. This is deliberately conservative: the current version has no automatic release/delete workflow. An operator must reconcile actual objects before any future reservation reclamation feature. Do not delete or rename the quota Durable Object, change its `account-free-budget-v1` identity, or recreate its namespace while objects remain.

## Dates and renewal

Set the gateway's `BILLING_CYCLE_DAY` (1–31) to the account's actual billing day, after checking Cloudflare Billing. If unset, the UI clearly labels calendar-month dates as **calendar-estimate**, not verified account billing dates. All dates use UTC. Days 29–31 clamp to shorter months; leap years are handled. The report supplies `asOf`, `cycle.start`, `cycle.end`, `cycle.nextRenewal`, `lastActivityAt`, and up to 13 cycle usage summaries. At the first request in a new cycle, its operation counters start at zero automatically; no cron or unsafe reset endpoint exists.

An additional **rolling 35-day** allowance remains enforced across renewal. This prevents billing-day/timezone mistakes or changing the configured date from granting two free allowances in one provider period. Consequently a new cycle can show zero monthly operations but have less available quota until the rolling window clears. Availability is sacrificed before exceeding the guardrail.

## Authenticated admin endpoints

| Endpoint | Result |
|---|---|
| `GET /api/admin/storage/limits` | Limits, current storage reservations, cycle dates, monthly and rolling operation usage, remaining budget. |
| `GET /api/admin/storage/usage` | Alias for current usage and renewal details. |
| `GET /api/admin/storage/cycles` | Retained cycle operation totals. |
| `POST /api/admin/r2/{titleId}/reserve` | Reserve encoded bundle `bytes` and `files` before granting an expiring upload capability. |
| `GET /api/admin/r2/{titleId}/reservation` | Renew capability for the same reservation; never creates additional storage allowance. |
| `POST /api/admin/r2/{titleId}/publish` | Verify all HLS references/assets, attest rights and captions, publish the actual video. |

All require verified application admin identity. Signed capabilities contain only a scope, bundle UUID, audience and expiry. They are credentials: never log or share them. Usage reads do not call R2, although Worker/Durable Object requests still consume their own platform allowance. There is intentionally no client/admin counter-reset endpoint.

## Deployment requirements

1. Use a **new empty dedicated private Standard R2 bucket**. Disable `r2.dev`, public/custom-domain bucket access, external S3 credentials, automatic tiering, replication, Sippy and other integrations. This application ledger cannot observe dashboard uploads, other apps/buckets, lifecycle operations or pre-existing account usage. The free allowance is account-wide: another bucket can consume it.
2. Review `media-gateway/wrangler.jsonc`, set the real bucket name and frontend `APP_ORIGIN`, and use the **Workers Free** plan. SQLite Durable Objects are supported on Free. Their free limits can stop service; they are separate from R2 limits. Do not deploy this to paid Workers and assume R2 caps also cap Worker/DO charges. Do not enable log shipping or paid add-ons.
3. Set an independently generated secret of at least 43 characters as `MEDIA_CAPABILITY_SECRET` in the gateway and the same value as `R2_MEDIA_CAPABILITY_SECRET` in Spring. Set `R2_MEDIA_ORIGIN` to the gateway's HTTPS origin. Keep all secrets out of Git. Set `BILLING_CYCLE_DAY` in the gateway to the account's real billing day.
4. Keep the bucket accessible only through the gateway's R2 binding. Check Cloudflare budget alerts and account usage independently. Alerts notify; this code does not claim to install a provider billing hard cap. Real account deployment must verify these requirements before accepting uploads.
5. Encode GB originals **locally**, not on the sleeping free Java API: `python3 tools/encode_hls.py /absolute/movie.mp4 /new/output/folder`. Requires FFmpeg/ffprobe. The script prepares 360p/720p/1080p where the source supports them, without upscaling. Output size may exceed the free allowance; the script and gateway reject it. Originals stay on local disk. In Video studio choose/create the movie or episode draft, select the encoded HLS folder, upload, review and publish. Episodes are separate bundles linked to a series.

## Failure behavior and proof limits

Quota exhaustion denies new corresponding R2 operations. Missing/expired capability, ledger outage or storage failure prevents successful access. Storage is never read or written before a budget grant. Reads are deliberately not served from an unmetered public origin. The free-budget gateway is a demo cost-control path; its central global ledger and platform quotas are not a million-viewer configuration.

Tests cover reservation races with the real local Durable Object/SQLite and R2 emulator, immutable objects, HLS-reference validation, scoped reads, operation counts, hard boundaries, automatic cycle rollover, rolling-window protection and leap-year dates. These are local tests, not proof of configured Cloudflare account protection or a real GB upload.

Sources checked October 21, 2026: [R2 pricing and operation classes](https://developers.cloudflare.com/r2/pricing/), [Workers Free limits](https://developers.cloudflare.com/workers/platform/limits/), [Durable Objects pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/).

## Live deployment verification — October 22, 2026 UTC

The exact gateway source is deployed at `https://aether-private-media.vaibhav-bansal2020.workers.dev`, bound to the dedicated private Standard bucket `aether-private-media`. A live synthetic HLS bundle (592,684 bytes, four assets) was reserved, uploaded, completed and read through an expiring signed capability. The ledger reported four Class A operations and one Class B operation. No GB upload or million-viewer test is claimed. The billing anchor remains unverified (`calendar-estimate`); rolling 35-day enforcement remains active. The protected Render environment now contains the matching signing secret and gateway origin; application release `c61f1b3` is live on Render. Admin endpoint access still requires a real owner account with an explicitly assigned administrator role. Application control requests identify themselves with `Aether-Media-Integration/1.0` because Cloudflare rejected Python's default client identification with error 1010.
