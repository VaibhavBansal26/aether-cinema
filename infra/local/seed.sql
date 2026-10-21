-- Disposable integration fixtures only. Do not apply to production.
INSERT INTO users(id,subject,role) VALUES
 ('10000000-0000-0000-0000-000000000001','viewer','viewer'),
 ('10000000-0000-0000-0000-000000000002','admin','admin'),
 ('10000000-0000-0000-0000-000000000003','other','viewer') ON CONFLICT DO NOTHING;
INSERT INTO profiles(id,user_id,name) VALUES
 ('20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000001','Local viewer'),
 ('20000000-0000-0000-0000-000000000003','10000000-0000-0000-0000-000000000003','Other viewer') ON CONFLICT DO NOTHING;
INSERT INTO titles(id,name,synopsis,genre,status,published_at,kind,editorial_score) VALUES
 ('30000000-0000-0000-0000-000000000001','Orbit','A crew explores a distant planet','Sci-Fi','published',now(),'films',.9),
 ('30000000-0000-0000-0000-000000000002','Deep Space','A crew searches the stars','Sci-Fi','published',now(),'series',.8),
 ('30000000-0000-0000-0000-000000000003','Homeward','A family journey','Drama','published',now(),'films',.7) ON CONFLICT DO NOTHING;
