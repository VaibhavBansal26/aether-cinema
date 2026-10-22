ALTER TABLE checkout_requests ALTER COLUMN url DROP NOT NULL;
ALTER TABLE checkout_requests ADD COLUMN created_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE checkout_requests ADD COLUMN stripe_session_id text UNIQUE;
ALTER TABLE checkout_requests ADD COLUMN state text NOT NULL DEFAULT 'ready' CHECK(state IN ('creating','ready','closed'));
CREATE UNIQUE INDEX checkout_one_open_intent ON checkout_requests(user_id) WHERE state<>'closed';
