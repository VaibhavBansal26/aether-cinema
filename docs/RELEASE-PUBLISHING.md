# Movies, series, episodes and scheduled releases

Open **Creator studio** from the footer, or from the administrator account page. It opens the live publishing workflow directly; the older device-only draft form is removed.

## Content structure

- **Movie**: one video and its artwork, genre and synopsis.
- **Series collection**: metadata for a show, with independently uploaded episodes.
- **Series episode**: select an existing series, season number and episode number. Episode numbers are unique within a series/season. A movie has no episode number.

Create the series first, then create each episode with its own video bundle. The parent-series picker is queried separately from the bounded title list, and editable drafts appear ahead of the published catalog. Private R2 is the primary upload path; paid Stream upload and encoding are an optional expandable section.

## Publish now or schedule

Select the content draft and choose **Publish now after review** or **Schedule a release**. Scheduled publication requires a future date/time within two years. Choose the browser timezone or UTC. The UI displays the resulting UTC instant alongside the browser-local time; nonexistent local times during daylight-saving changes are rejected. Ambiguous fall-back times use the browser's earlier offset: use UTC when a specific offset is required.

Complete encoding, rights and caption reviews before submitting publication. A future date alone never makes an unfinished video publishable. R2 and managed Stream share the release controls. A series launch needs a ready episode released or scheduled at/before the collection's launch. Schedule the first episode, then the collection. Subsequent episodes can have their own release days and times.

Schedules are committed as `status=scheduled` with `published_at` (UTC) and `release_time_zone`. They survive restarts. A dedicated scheduler polls every second and atomically activates up to 100 due records with `FOR UPDATE SKIP LOCKED`; concurrent replicas cannot activate/audit the same record twice. Broker retries run on a separate scheduler. Activation triggers catalog revision invalidation for recommendation caches. Catalog queries and playback authorization also check release timestamps, and episodes require their parent series to be released.

Select an existing scheduled title to load its saved UTC date/time. **Update selected scheduled release** changes it. Selecting Publish now changes the button to an explicit immediate-release action; only already-reviewed scheduled content can use that action. Review checkboxes reset when switching titles.

## API

`POST /api/admin/videos/{titleId}/publish` accepts the existing review fields plus optional `releaseAt` (ISO-8601 instant) and `releaseTimeZone` (IANA zone). `POST /api/admin/r2/{titleId}/publish` adds the same fields alongside `bundleId` and review fields. Omitting `releaseAt` publishes immediately after verification.

`POST /api/admin/videos/{titleId}/release` changes an already-reviewed scheduled release. Body: `{"releaseAt":"2026-11-01T18:30:00Z","releaseTimeZone":"UTC"}`. A null/omitted instant explicitly releases it now. All routes require the database-owned admin role. `GET /api/admin/videos` includes kind, parent series, season, episode, release status, timestamp and zone.

## Operational limits

Apply migration `010_release_schedule.sql` before deploying the application. The database clock must be synchronized. Publication is never authorized early, but activation can be delayed by worker downtime, a large due backlog or database contention. Render Free can sleep; it is not an exact-time release SLA. An always-running worker and monitored release lag are required for reliable production launches. Storage reservations and operation caps still apply to scheduled videos.

## Validation

A full 72-test backend suite passed; a subsequent 23-test real-PostgreSQL run verified the final draft-list query and scheduling paths. Nine frontend tests passed. Checks cover early episode playback rejection, parent-series visibility, due activation/audit idempotence, admin-only rescheduling, invalid/past dates, and UTC conversion. Browser layout checks use explicitly labeled local example data; they are not a real movie upload.
