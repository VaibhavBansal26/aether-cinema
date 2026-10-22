#!/bin/sh
set -eu
# Repeatable, checksummed local migration runner. The signer and seed are TEST ONLY.
for path in /schema/schema.sql /schema/migrations/*.sql; do
 version=$(basename "$path")
 checksum=$(sha256sum "$path" | cut -d ' ' -f 1)
 psql -v ON_ERROR_STOP=1 --single-transaction -v version="$version" -v checksum="$checksum" <<SQL
SELECT pg_advisory_xact_lock(928174);
CREATE TABLE IF NOT EXISTS schema_migrations(version text PRIMARY KEY,checksum text NOT NULL,applied_at timestamptz NOT NULL DEFAULT now());
SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version=:'version') AS applied \gset
\if :applied
SELECT checksum=:'checksum' AS unchanged FROM schema_migrations WHERE version=:'version' \gset
\if :unchanged
\else
\echo Migration checksum mismatch
\quit 1
\endif
\else
\i $path
INSERT INTO schema_migrations(version,checksum) VALUES(:'version',:'checksum');
\endif
SQL
done
psql -v ON_ERROR_STOP=1 --single-transaction -f /fixtures/seed.sql
