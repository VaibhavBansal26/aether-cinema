import {LIMITS,debitCycle,budgetStatus,reserveBytes,assetName,manifestReferences} from './budget.js';
const json=(data,status=200)=>Response.json(data,{status,headers:{'Cache-Control':'no-store'}});
const decode=value=>Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
/** Only Spring-issued expiring HS256 capabilities reach the bucket. No public R2 URL or S3 presign. */
async function verify(token,secret){if(!secret||secret.length<43)throw new Error('Gateway not configured');const parts=token.split('.');if(parts.length!==3)throw new Error('Invalid capability');const header=JSON.parse(new TextDecoder().decode(decode(parts[0])));if(header.alg!=='HS256')throw new Error('Invalid algorithm');const key=await crypto.subtle.importKey('raw',new TextEncoder().encode(secret),{name:'HMAC',hash:'SHA-256'},false,['verify']);if(!await crypto.subtle.verify('HMAC',key,decode(parts[2]),new TextEncoder().encode(parts[0]+'.'+parts[1])))throw new Error('Invalid signature');const claims=JSON.parse(new TextDecoder().decode(decode(parts[1])));if(claims.aud!=='aether-media'||!Number.isFinite(claims.exp)||claims.exp<=Date.now()/1000)throw new Error('Expired capability');return claims;}
/** One account-wide Durable Object is the authority; Redis evictions and API replicas cannot reset it. */
export class MediaBudget {
 constructor(state,env){this.state=state;this.env=env;}
 async fetch(request){try{const input=await request.json();return await this.state.storage.transaction(async tx=>{
  let budget=await tx.get('budget')||{allocated:0,buckets:[]};const key='bundle:'+input.bundle;
  const anchor=Number(this.env.BILLING_CYCLE_DAY)||1;if(!Number.isInteger(anchor)||anchor<1||anchor>31)throw new Error('Invalid billing-cycle configuration');
  if(input.action==='status')return json(budgetStatus(budget,Date.now(),anchor,!!this.env.BILLING_CYCLE_DAY));
  if(!/^[0-9a-f-]{36}$/.test(input.bundle||''))throw new Error('Invalid bundle');
  if(input.action==='reserve'){
   if(await tx.get(key))throw new Error('Bundle already reserved');if(!Number.isInteger(input.files)||input.files<1||input.files>LIMITS.files)throw new Error('Invalid file count');
   budget.allocated=reserveBytes(budget.allocated,input.bytes);await tx.put(key,{bytes:input.bytes,files:input.files,written:0,count:0,complete:false});await tx.put('budget',budget);return json({reserved:input.bytes});
  }
  const bundle=await tx.get(key);if(!bundle)throw new Error('Unknown bundle');
  if(input.action==='put'){
   if(bundle.complete||!assetName(input.name)||!Number.isInteger(input.bytes)||input.bytes<1||input.bytes>LIMITS.part)throw new Error('Invalid asset');
   const assetKey='asset:'+input.bundle+':'+input.name;const existing=await tx.get(assetKey);
   if(existing&&(existing.bytes!==input.bytes||existing.hash!==input.hash))throw new Error('Asset is immutable');
   if(!existing){if(bundle.written+input.bytes>bundle.bytes||bundle.count>=bundle.files)throw new Error('Reservation exceeded');bundle.written+=input.bytes;bundle.count++;await tx.put(key,bundle);}
   budget=debitCycle(budget,'classA',Date.now(),anchor);await tx.put('budget',budget);await tx.put(assetKey,{bytes:input.bytes,hash:input.hash,ready:existing?.ready||false,refs:input.refs||[]});return json({allowed:true});
  }
  if(input.action==='stored'){const k='asset:'+input.bundle+':'+input.name;const asset=await tx.get(k);if(!asset)throw new Error('Unreserved asset');asset.ready=true;await tx.put(k,asset);return json({stored:true});}
  if(input.action==='complete'){
   if(bundle.count!==bundle.files||bundle.written!==bundle.bytes)throw new Error('Upload is incomplete');
   const assets=await tx.list({prefix:'asset:'+input.bundle+':',limit:LIMITS.files});const master=assets.get('asset:'+input.bundle+':master.m3u8');if(!master?.ready)throw new Error('Missing master playlist');
   for(const asset of assets.values()){if(!asset.ready)throw new Error('Unfinished asset');for(const ref of asset.refs)if(!assets.get('asset:'+input.bundle+':'+ref)?.ready)throw new Error('Missing playlist reference');}
   bundle.complete=true;await tx.put(key,bundle);return json({ready:true});
  }
  if(input.action==='read'){
   if(!bundle.complete||!assetName(input.name))throw new Error('Media is unavailable');const asset=await tx.get('asset:'+input.bundle+':'+input.name);if(!asset?.ready)throw new Error('Media is unavailable');budget=debitCycle(budget,'classB',Date.now(),anchor);await tx.put('budget',budget);return json({allowed:true});
  }
  throw new Error('Unsupported budget operation');
 });}catch(error){return json({error:error.message},429);}}
}
export default {async fetch(request,env){
 const origin=request.headers.get('Origin');const cors=origin===env.APP_ORIGIN?{'Access-Control-Allow-Origin':origin,'Access-Control-Allow-Methods':'GET,PUT,POST,OPTIONS','Access-Control-Allow-Headers':'Content-Type,Range','Access-Control-Expose-Headers':'Content-Length,Content-Range,Accept-Ranges','Vary':'Origin'}:{};
 const answer=response=>{const headers=new Headers(response.headers);for(const [k,v]of Object.entries(cors))headers.set(k,v);headers.set('Cache-Control','no-store');return new Response(response.body,{status:response.status,headers});};
 if(request.method==='OPTIONS')return answer(new Response(null,{status:204}));
 try{
  const url=new URL(request.url);const [_,token,bundle,name]=url.pathname.split('/');const claims=await verify(token,env.MEDIA_CAPABILITY_SECRET);
  if(claims.bundle!==bundle&&claims.action!=='status')throw new Error('Capability scope mismatch');
  const stub=env.BUDGET.get(env.BUDGET.idFromName('account-free-budget-v1'));
  const budget=async input=>{const response=await stub.fetch('https://budget.internal',{method:'POST',body:JSON.stringify({...input,bundle})});const data=await response.json();if(!response.ok)throw new Error(data.error);return data;};
  if(claims.action==='status'&&request.method==='POST')return answer(json(await budget({action:'status'})));
  if(claims.action==='reserve'&&request.method==='POST'){const input=await request.json();return answer(json(await budget({action:'reserve',bytes:input.bytes,files:input.files})));}
  if(claims.action==='complete'&&request.method==='POST')return answer(json(await budget({action:'complete'})));
  if(claims.action==='upload'&&request.method==='PUT'&&assetName(name)){
   // Inspect a bounded segment, not a GB original. Content-Length is an early rejection, never trusted as actual size.
   if(Number(request.headers.get('Content-Length'))>LIMITS.part)throw new Error('Segment exceeds 16 MiB');
   const reader=request.body?.getReader();if(!reader)throw new Error('Empty segment');const chunks=[];let bytes=0;
   for(;;){const {done,value}=await reader.read();if(done)break;bytes+=value.byteLength;if(bytes>LIMITS.part){await reader.cancel();throw new Error('Segment exceeds 16 MiB');}chunks.push(value);}
   const data=new Uint8Array(bytes);let offset=0;for(const chunk of chunks){data.set(chunk,offset);offset+=chunk.byteLength;}
   const hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',data))).map(x=>x.toString(16).padStart(2,'0')).join('');
   const refs=name.endsWith('.m3u8')?manifestReferences(new TextDecoder().decode(data)):[];
   await budget({action:'put',name,bytes,hash,refs});await env.MEDIA.put(bundle+'/'+name,data,{httpMetadata:{contentType:name.endsWith('.m3u8')?'application/vnd.apple.mpegurl':name.endsWith('.ts')?'video/mp2t':'video/mp4'}});await budget({action:'stored',name});return answer(json({stored:true}));
  }
  if(claims.action==='read'&&request.method==='GET'&&assetName(name)){
   await budget({action:'read',name});const object=await env.MEDIA.get(bundle+'/'+name,{range:request.headers});if(!object)return answer(json({error:'Media unavailable'},404));const headers=new Headers();object.writeHttpMetadata(headers);headers.set('Accept-Ranges','bytes');headers.set('Content-Length',String(object.range?.length??object.size));if(request.headers.has('Range')&&object.range)headers.set('Content-Range',`bytes ${object.range.offset}-${object.range.offset+object.range.length-1}/${object.size}`);return answer(new Response(object.body,{status:request.headers.has('Range')&&object.range?206:200,headers}));
  }
  return answer(json({error:'Unsupported media operation'},403));
 }catch(error){return answer(json({error:error.message||'Media denied'},429));}
}};
