-- Visibility is distinct from an unfinished draft; retain lifecycle for archive recovery.
ALTER TABLE titles DROP CONSTRAINT titles_status_check;
ALTER TABLE titles ADD CONSTRAINT titles_status_check CHECK(status IN ('draft','processing','review','scheduled','published','hidden','archived'));
ALTER TABLE titles ADD COLUMN archived_from text;
ALTER TABLE titles ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
CREATE INDEX titles_management_order ON titles(created_at DESC,id);
