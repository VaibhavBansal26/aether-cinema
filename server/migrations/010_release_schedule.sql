-- Release instants are authoritative UTC timestamps; the zone is retained for editorial display.
ALTER TABLE titles DROP CONSTRAINT titles_status_check;
ALTER TABLE titles ADD CONSTRAINT titles_status_check CHECK(status IN ('draft','processing','review','scheduled','published','archived'));
ALTER TABLE titles ADD COLUMN release_time_zone text NOT NULL DEFAULT 'UTC';
ALTER TABLE titles ADD CONSTRAINT scheduled_release_required CHECK(status<>'scheduled' OR published_at IS NOT NULL);
CREATE INDEX titles_due_release ON titles(published_at,id) WHERE status='scheduled';
