"""Disposable TEMP tables benchmark the previous catalog-wide history join against bounded profile slices."""
import subprocess,json,re,statistics,pathlib
sql="""
CREATE TEMP TABLE titles(LIKE public.titles INCLUDING ALL);
CREATE TEMP TABLE playback(LIKE public.playback INCLUDING ALL);
CREATE TEMP TABLE reactions(LIKE public.reactions INCLUDING ALL);
INSERT INTO titles(id,name,synopsis,genre,status,published_at) SELECT md5(n::text)::uuid,'Benchmark '||n,'Synthetic catalog synopsis','Drama','published',now()-make_interval(secs=>n) FROM generate_series(1,100000) n;
INSERT INTO playback(profile_id,title_id,position_seconds,duration_seconds) SELECT '20000000-0000-0000-0000-000000000001',md5(n::text)::uuid,20,100 FROM generate_series(1,100) n;
ANALYZE titles;ANALYZE playback;ANALYZE reactions;
"""
p="'20000000-0000-0000-0000-000000000001'"
old=f"SELECT t.id,coalesce(p.position_seconds,0) FROM titles t LEFT JOIN playback p ON p.title_id=t.id AND p.profile_id={p} LEFT JOIN reactions r ON r.title_id=t.id AND r.profile_id={p} WHERE t.status='published' AND (p.title_id IS NOT NULL OR r.title_id IS NOT NULL) ORDER BY greatest(coalesce(p.updated_at,to_timestamp(0)),coalesce(r.updated_at,to_timestamp(0))) DESC LIMIT 1000"
new=f"WITH recent AS (SELECT title_id FROM (SELECT title_id FROM playback WHERE profile_id={p} ORDER BY updated_at DESC LIMIT 1000) p UNION SELECT title_id FROM (SELECT title_id FROM reactions WHERE profile_id={p} ORDER BY updated_at DESC LIMIT 1000) r) SELECT t.id,coalesce(p.position_seconds,0) FROM recent x JOIN titles t ON t.id=x.title_id LEFT JOIN playback p ON p.title_id=t.id AND p.profile_id={p} LEFT JOIN reactions r ON r.title_id=t.id AND r.profile_id={p} WHERE t.status='published' ORDER BY greatest(coalesce(p.updated_at,to_timestamp(0)),coalesce(r.updated_at,to_timestamp(0))) DESC LIMIT 1000"
for _ in range(3):
 for query in [old,new]:sql+='EXPLAIN(ANALYZE,BUFFERS,FORMAT JSON) '+query+';\n'
raw=subprocess.check_output(['docker','compose','-f','aether/infra/compose.stack.yml','exec','-T','postgres','psql','-U','aether_test','-d','aether','-At'],input=sql,text=True)
plans=[];decoder=json.JSONDecoder();pos=0
while (pos:=raw.find('[',pos))>=0:
 value,n=decoder.raw_decode(raw[pos:]);plans.append(value[0]);pos+=n
result={'synthetic_catalog_titles':100000,'profile_history_rows':100,'runs':3,'previous_median_ms':statistics.median(x['Execution Time'] for x in plans[::2]),'bounded_median_ms':statistics.median(x['Execution Time'] for x in plans[1::2]),'previous_runs_ms':[x['Execution Time'] for x in plans[::2]],'bounded_runs_ms':[x['Execution Time'] for x in plans[1::2]],'scope':'local PostgreSQL TEMP tables; not production latency or capacity'}
pathlib.Path('work/query-benchmark.json').write_text(json.dumps(result,indent=2));print(json.dumps(result))
