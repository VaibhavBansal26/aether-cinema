-- PostgreSQL 16+. Apply with a migration role, never the web application's role.
CREATE TABLE users (id uuid PRIMARY KEY, subject text UNIQUE NOT NULL, role text NOT NULL DEFAULT 'viewer' CHECK(role IN ('viewer','admin')));
CREATE TABLE profiles (id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES users(id), name varchar(80) NOT NULL);
CREATE TABLE titles (id uuid PRIMARY KEY, name varchar(120) NOT NULL, synopsis text NOT NULL, genre text NOT NULL, stream_uid text, status text NOT NULL DEFAULT 'draft' CHECK(status IN ('draft','processing','review','published','archived')), published_at timestamptz);
CREATE INDEX titles_published ON titles(published_at DESC,id) WHERE status='published';
CREATE INDEX titles_genre ON titles(genre,published_at DESC) WHERE status='published';
CREATE TABLE playback (profile_id uuid REFERENCES profiles(id),title_id uuid REFERENCES titles(id),position_seconds double precision NOT NULL CHECK(position_seconds>=0),duration_seconds double precision NOT NULL CHECK(duration_seconds>0),volume real NOT NULL DEFAULT 1 CHECK(volume BETWEEN 0 AND 1),muted boolean NOT NULL DEFAULT false,updated_at timestamptz NOT NULL DEFAULT now(),PRIMARY KEY(profile_id,title_id));
CREATE INDEX playback_recent ON playback(profile_id,updated_at DESC);
CREATE TABLE reactions (profile_id uuid REFERENCES profiles(id),title_id uuid REFERENCES titles(id),liked boolean NOT NULL DEFAULT false,saved boolean NOT NULL DEFAULT false,PRIMARY KEY(profile_id,title_id));
CREATE TABLE subscriptions (user_id uuid PRIMARY KEY REFERENCES users(id),customer_id text UNIQUE NOT NULL,stripe_subscription_id text UNIQUE,status text NOT NULL,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE stripe_events (id text PRIMARY KEY,processed_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE audit_log (id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,actor uuid REFERENCES users(id),action text NOT NULL,title_id uuid REFERENCES titles(id),created_at timestamptz NOT NULL DEFAULT now());
-- Grant application role only bounded SELECT/INSERT/UPDATE; no DDL. Identity must be provisioned by a trusted OIDC onboarding service.
