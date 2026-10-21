ALTER TABLE titles ADD COLUMN media_state text NOT NULL DEFAULT 'not_uploaded';
ALTER TABLE titles ADD COLUMN media_ready boolean NOT NULL DEFAULT false;
ALTER TABLE titles ADD COLUMN encoding_percent numeric NOT NULL DEFAULT 0 CHECK(encoding_percent BETWEEN 0 AND 100);
ALTER TABLE titles ADD COLUMN media_checked_at timestamptz;
CREATE UNIQUE INDEX titles_stream_uid_unique ON titles(stream_uid) WHERE stream_uid IS NOT NULL;
