-- Apply with migration owner before deploying the matching API.
ALTER TABLE subscriptions ADD COLUMN plan text NOT NULL DEFAULT 'essential' CHECK(plan IN ('essential','cinema','collective'));
ALTER TABLE subscriptions ADD COLUMN stream_limit integer NOT NULL DEFAULT 1 CHECK(stream_limit BETWEEN 1 AND 4);
CREATE TABLE playback_sessions (
 id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id),profile_id uuid NOT NULL REFERENCES profiles(id),
 title_id uuid NOT NULL REFERENCES titles(id),client_id uuid NOT NULL,sequence bigint NOT NULL DEFAULT 0,
 created_at timestamptz NOT NULL DEFAULT now(),expires_at timestamptz NOT NULL,released_at timestamptz,
 UNIQUE(user_id,client_id)
);
CREATE INDEX playback_sessions_active ON playback_sessions(user_id,expires_at) WHERE released_at IS NULL;
CREATE INDEX playback_sessions_cleanup ON playback_sessions(expires_at);
CREATE TABLE checkout_requests (
 user_id uuid NOT NULL REFERENCES users(id),request_id uuid NOT NULL,plan text NOT NULL,url text NOT NULL,
 expires_at timestamptz NOT NULL,PRIMARY KEY(user_id,request_id)
);
CREATE INDEX checkout_requests_pending ON checkout_requests(user_id,expires_at);
ALTER TABLE titles ADD COLUMN poster text NOT NULL DEFAULT 'signal-hero.jpg' CHECK(poster IN ('signal-hero.jpg','neon-poster.jpg','wild-poster.jpg','orbit-poster.jpg','coast-poster.jpg'));
ALTER TABLE titles ADD COLUMN series_id uuid REFERENCES titles(id);
ALTER TABLE titles ADD COLUMN season_number integer CHECK(season_number BETWEEN 1 AND 1000);
ALTER TABLE titles ADD COLUMN episode_number integer CHECK(episode_number BETWEEN 1 AND 10000);
ALTER TABLE titles DROP CONSTRAINT titles_kind_check;
ALTER TABLE titles ADD CONSTRAINT titles_kind_check CHECK(kind IN ('films','series','episode'));
ALTER TABLE titles ADD CONSTRAINT episode_metadata CHECK((kind='episode' AND series_id IS NOT NULL AND season_number IS NOT NULL AND episode_number IS NOT NULL) OR (kind<>'episode' AND series_id IS NULL AND season_number IS NULL AND episode_number IS NULL));
CREATE UNIQUE INDEX titles_episode_number ON titles(series_id,season_number,episode_number) WHERE kind='episode';
CREATE INDEX titles_episodes ON titles(series_id,season_number,episode_number) WHERE status='published';
CREATE INDEX reactions_recent ON reactions(profile_id,updated_at DESC,title_id);
CREATE INDEX titles_catalog_page ON titles(published_at DESC,id DESC) WHERE status='published' AND kind<>'episode';
CREATE TABLE catalog_revision(id integer PRIMARY KEY CHECK(id=1),revision bigint NOT NULL DEFAULT 0);
INSERT INTO catalog_revision(id) VALUES(1);
CREATE TABLE profile_revisions(profile_id uuid PRIMARY KEY REFERENCES profiles(id) ON DELETE CASCADE,revision bigint NOT NULL DEFAULT 0);
CREATE FUNCTION bump_profile_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 INSERT INTO profile_revisions(profile_id,revision) VALUES(NEW.profile_id,1) ON CONFLICT(profile_id) DO UPDATE SET revision=profile_revisions.revision+1;
 RETURN NEW;
END $$;
CREATE TRIGGER playback_revision AFTER INSERT OR UPDATE ON playback FOR EACH ROW EXECUTE FUNCTION bump_profile_revision();
CREATE TRIGGER reaction_revision AFTER INSERT OR UPDATE ON reactions FOR EACH ROW EXECUTE FUNCTION bump_profile_revision();
CREATE FUNCTION bump_catalog_revision() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN UPDATE catalog_revision SET revision=revision+1 WHERE id=1;RETURN NULL;END $$;
CREATE TRIGGER catalog_revision_changed AFTER INSERT OR UPDATE OR DELETE ON titles FOR EACH STATEMENT EXECUTE FUNCTION bump_catalog_revision();
-- Coalesce pending snapshot work. Keep historical completed/dead rows intact.
DELETE FROM activity_jobs a USING activity_jobs b WHERE a.state='pending' AND b.state='pending' AND a.profile_id=b.profile_id AND a.title_id=b.title_id AND a.kind=b.kind AND a.id>b.id;
CREATE UNIQUE INDEX activity_jobs_coalesce ON activity_jobs(profile_id,title_id,kind) WHERE state='pending';

CREATE INDEX activity_jobs_profile_pending ON activity_jobs(profile_id) WHERE state='pending';
