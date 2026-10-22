ALTER TABLE activity_jobs DROP CONSTRAINT activity_jobs_state_check;
ALTER TABLE activity_jobs ADD CONSTRAINT activity_jobs_state_check CHECK(state IN ('pending','publishing','published','done','dead'));
ALTER TABLE activity_jobs ADD COLUMN claim_id uuid;
ALTER TABLE activity_jobs ADD COLUMN claim_until timestamptz;
CREATE INDEX activity_jobs_expired_claim ON activity_jobs(claim_until) WHERE state='publishing';
CREATE TABLE consumed_activity_events(event_id bigint PRIMARY KEY,consumed_at timestamptz NOT NULL DEFAULT now());

DROP INDEX activity_jobs_profile_pending;
CREATE INDEX activity_jobs_profile_unprocessed ON activity_jobs(profile_id) WHERE state IN ('pending','publishing','published');
