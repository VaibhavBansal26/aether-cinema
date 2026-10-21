-- Apply once using the migration role, after 002. The queue and user writes share PostgreSQL WAL.
CREATE TABLE activity_jobs (
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 profile_id uuid NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
 title_id uuid NOT NULL REFERENCES titles(id) ON DELETE CASCADE,
 kind text NOT NULL CHECK(kind IN ('progress','reaction')),
 created_at timestamptz NOT NULL DEFAULT now(),
 available_at timestamptz NOT NULL DEFAULT now(),
 attempts integer NOT NULL DEFAULT 0,
 state text NOT NULL DEFAULT 'pending' CHECK(state IN ('pending','done','dead'))
);
CREATE INDEX activity_jobs_pending ON activity_jobs(available_at,id) WHERE state='pending';
CREATE INDEX activity_jobs_cleanup ON activity_jobs(created_at) WHERE state='done';
CREATE TABLE profile_activity (
 profile_id uuid PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,
 events bigint NOT NULL DEFAULT 0,
 last_event_id bigint NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now()
);
