CREATE TABLE r2_uploads (
 bundle_id uuid PRIMARY KEY,title_id uuid NOT NULL REFERENCES titles(id),reserved_bytes bigint NOT NULL CHECK(reserved_bytes BETWEEN 1 AND 8000000000),
 files integer NOT NULL CHECK(files BETWEEN 1 AND 20000),state text NOT NULL DEFAULT 'reserved' CHECK(state IN ('reserved','published')),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX r2_uploads_title_reserved ON r2_uploads(title_id) WHERE state='reserved';
