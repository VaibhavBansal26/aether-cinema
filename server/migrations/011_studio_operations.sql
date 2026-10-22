-- Retain all historic Stripe prices so an admin can change prices for new members only.
CREATE TABLE billing_plans(id varchar(40) PRIMARY KEY,name varchar(80) NOT NULL,stream_limit integer NOT NULL CHECK(stream_limit BETWEEN 1 AND 4),current_price text UNIQUE,active boolean NOT NULL DEFAULT true,updated_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE billing_price_versions(price_id text PRIMARY KEY,plan_id varchar(40) NOT NULL REFERENCES billing_plans(id),stream_limit integer NOT NULL CHECK(stream_limit BETWEEN 1 AND 4),created_at timestamptz NOT NULL DEFAULT now());
ALTER TABLE subscriptions DROP CONSTRAINT subscriptions_plan_check;
ALTER TABLE subscriptions ADD COLUMN price_id text;
ALTER TABLE subscriptions ADD COLUMN current_period_end timestamptz;
ALTER TABLE subscriptions ADD COLUMN cancel_at_period_end boolean NOT NULL DEFAULT false;
CREATE TABLE billing_plan_changes(request_id uuid PRIMARY KEY,plan_id varchar(40) NOT NULL,price_id text NOT NULL,request_hash text NOT NULL,actor uuid REFERENCES users(id),created_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE title_artwork(title_id uuid PRIMARY KEY REFERENCES titles(id),jpeg bytea NOT NULL CHECK(octet_length(jpeg) BETWEEN 1 AND 250000),updated_at timestamptz NOT NULL DEFAULT now());
CREATE INDEX playback_sessions_started ON playback_sessions(created_at DESC,title_id,user_id);
CREATE INDEX playback_sessions_live ON playback_sessions(expires_at,title_id) WHERE released_at IS NULL;

ALTER TABLE checkout_requests ADD COLUMN price_id text;
