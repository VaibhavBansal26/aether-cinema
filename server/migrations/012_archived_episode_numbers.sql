-- Archived episodes retain history but no longer occupy an editorial episode slot.
DROP INDEX titles_episode_number;
CREATE UNIQUE INDEX titles_episode_number ON titles(series_id,season_number,episode_number) WHERE kind='episode' AND status<>'archived';
