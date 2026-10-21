-- Apply once with the migration role after schema.sql. Runtime roles must not own DDL.
ALTER TABLE titles ADD COLUMN kind varchar(10) NOT NULL DEFAULT 'films' CHECK(kind IN ('films','series'));
ALTER TABLE titles ADD COLUMN editorial_score double precision NOT NULL DEFAULT 0.5 CHECK(editorial_score BETWEEN 0 AND 1);
ALTER TABLE reactions ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
CREATE TABLE recommendation_settings (
  id smallint PRIMARY KEY CHECK(id=1),
  enabled boolean NOT NULL DEFAULT false,
  provider varchar(20) NOT NULL DEFAULT 'none' CHECK(provider IN ('none','ollama')),
  model varchar(120) NOT NULL DEFAULT '',
  revision bigint NOT NULL DEFAULT 0,
  updated_by uuid REFERENCES users(id),
  updated_at timestamptz NOT NULL DEFAULT now(),
  CHECK(NOT enabled OR (provider='ollama' AND length(model)>0))
);
INSERT INTO recommendation_settings(id) VALUES(1);
