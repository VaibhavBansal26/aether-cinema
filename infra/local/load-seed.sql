-- Disposable local load identities only; never deploy the local signer or this data publicly.
INSERT INTO users(id,subject,role) SELECT ('10000000-0000-0000-0001-'||lpad(n::text,12,'0'))::uuid,'load-'||n,'viewer' FROM generate_series(1,100) n ON CONFLICT DO NOTHING;
INSERT INTO profiles(id,user_id,name) SELECT ('20000000-0000-0000-0001-'||lpad(n::text,12,'0'))::uuid,('10000000-0000-0000-0001-'||lpad(n::text,12,'0'))::uuid,'Load fixture' FROM generate_series(1,100) n ON CONFLICT DO NOTHING;
