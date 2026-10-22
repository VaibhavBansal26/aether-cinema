import test from 'node:test';import assert from 'node:assert/strict';import {Miniflare} from 'miniflare';import {createHmac,randomUUID} from 'node:crypto';
const secret='local-test-only-shared-secret-at-least-forty-three-characters';
function token(action,bundle){const enc=x=>Buffer.from(JSON.stringify(x)).toString('base64url');const input=enc({alg:'HS256'})+'.'+enc({aud:'aether-media',action,bundle,exp:Math.floor(Date.now()/1000)+300});return input+'.'+createHmac('sha256',secret).update(input).digest('base64url');}
test('real durable-object storage serializes reservations and guards private R2 writes and reads',async()=>{
 const mf=new Miniflare({modules:true,scriptPath:new URL('../build/worker.mjs',import.meta.url).pathname,compatibilityDate:'2026-07-30',r2Buckets:['MEDIA'],durableObjects:{BUDGET:{className:'MediaBudget',useSQLite:true}},bindings:{MEDIA_CAPABILITY_SECRET:secret,APP_ORIGIN:'https://aether.example',BILLING_CYCLE_DAY:'21'}});
 const call=(action,bundle,body)=>mf.dispatchFetch('https://media.example/'+token(action,bundle)+'/'+bundle,{method:'POST',body:JSON.stringify(body)});
 try{
  const reservations=await Promise.all(Array.from({length:8},()=>call('reserve',randomUUID(),{bytes:1_000_000_000,files:1})));assert.equal(reservations.filter(r=>r.status===200).length,8);
  assert.equal((await call('reserve',randomUUID(),{bytes:1,files:1})).status,429);
  const usage=await (await call('status',randomUUID(),{})).json();assert.equal(usage.storage.allocatedBytes,8_000_000_000);assert.equal(usage.cycle.billingDay,21);
 }finally{await mf.dispose();}
});
test('completed HLS bundles enforce immutable assets, references, scoped reads, and durable operation counters',async()=>{
 const mf=new Miniflare({modules:true,scriptPath:new URL('../build/worker.mjs',import.meta.url).pathname,compatibilityDate:'2026-07-30',r2Buckets:['MEDIA'],durableObjects:{BUDGET:{className:'MediaBudget',useSQLite:true}},bindings:{MEDIA_CAPABILITY_SECRET:secret,APP_ORIGIN:'https://aether.example'}});
 const id=randomUUID();const control=(action,body={})=>mf.dispatchFetch('https://media.example/'+token(action,id)+'/'+id,{method:'POST',body:JSON.stringify(body)});
 const put=(name,body)=>mf.dispatchFetch('https://media.example/'+token('upload',id)+'/'+id+'/'+name,{method:'PUT',body});
 try{
  const master='#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nv0.m3u8\n',variant='#EXTM3U\n#EXTINF:4,\nv0_000001.ts\n#EXT-X-ENDLIST\n',segment='synthetic segment';
  assert.equal((await control('reserve',{bytes:Buffer.byteLength(master+variant+segment),files:3})).status,200);
  assert.equal((await put('master.m3u8',master)).status,200);assert.equal((await control('complete')).status,429);
  assert.equal((await put('master.m3u8',master.replace('800000','900000'))).status,429);
  assert.equal((await put('v0.m3u8',variant)).status,200);assert.equal((await put('v0_000001.ts',segment)).status,200);assert.equal((await control('complete')).status,200);
  const read=await mf.dispatchFetch('https://media.example/'+token('read',id)+'/'+id+'/master.m3u8');assert.equal(read.status,200);assert.equal(await read.text(),master);
  const noToken=await mf.dispatchFetch('https://media.example/not-a-token/'+id+'/master.m3u8');assert.equal(noToken.status,429);
  const usage=await(await control('status')).json();assert.equal(usage.operations.classA.used,3);assert.equal(usage.operations.classB.used,1);
 }finally{await mf.dispose();}
});
