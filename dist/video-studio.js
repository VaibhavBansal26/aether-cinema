import {apiRequest} from './api-client.js';
/** Enforce provider-only capability URLs before sending file bytes or embedding a preview. */
export function uploadEndpoint(value){
 const url=new URL(value);
 if(url.protocol!=='https:'||url.username||url.password||url.port||!(url.hostname==='upload.videodelivery.net'||url.hostname==='upload.cloudflarestream.com'||url.hostname.endsWith('.cloudflarestream.com')))throw new Error('Invalid provider upload address');
 return url.href;
}
/** Bounded-memory tus uploader. HEAD discovers the committed offset after pause or connection loss. */
export class ResumableVideoUpload {
 constructor(file,url,onProgress){this.file=file;this.url=uploadEndpoint(url);this.onProgress=onProgress;this.paused=false;this.request=null;this.running=false;}
 pause(){this.paused=true;this.request?.abort();}
 async run(){
  if(this.running)return false;this.running=true;this.paused=false;
  try{
   const head=await fetch(this.url,{method:'HEAD',headers:{'Tus-Resumable':'1.0.0'},credentials:'omit',cache:'no-store',signal:AbortSignal.timeout(20000)});
   if(!head.ok)throw new Error('Upload session expired or unavailable. Refresh processing status before creating a new draft.');
   const raw=head.headers.get('Upload-Offset');let offset=raw===null?NaN:Number(raw);
   if(!Number.isSafeInteger(offset)||offset<0||offset>this.file.size)throw new Error('Provider returned an invalid upload offset');
   this.onProgress(offset,this.file.size);
   while(offset<this.file.size&&!this.paused){
    const end=Math.min(offset+8*1024*1024,this.file.size);
    offset=await this.chunk(offset,end);this.onProgress(offset,this.file.size);
   }
   return !this.paused&&offset===this.file.size;
  }finally{this.running=false;this.request=null;}
 }
 chunk(offset,end){return new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest();this.request=xhr;xhr.open('PATCH',this.url);xhr.timeout=120000;
  xhr.setRequestHeader('Tus-Resumable','1.0.0');xhr.setRequestHeader('Upload-Offset',String(offset));xhr.setRequestHeader('Content-Type','application/offset+octet-stream');
  xhr.upload.onprogress=e=>this.onProgress(offset+e.loaded,this.file.size);
  xhr.onload=()=>{const raw=xhr.getResponseHeader('Upload-Offset'),next=raw===null?NaN:Number(raw);if(xhr.status!==204||next!==end)reject(new Error('Upload interrupted. Choose Resume to continue from the server’s saved position.'));else resolve(next);};
  xhr.onerror=xhr.ontimeout=()=>reject(new Error('Connection interrupted. Choose Resume to continue.'));
  xhr.onabort=()=>reject(new Error('Upload paused. Keep this window open and choose Resume to continue.'));
  xhr.send(this.file.slice(offset,end));
 });}
}
/** Live studio. All actions remain disabled without a protected admin session. */
export async function openVideoStudio({modal,toast,onCleanup}){
 modal(`<div class="dialog-padding video-studio"><p class="eyebrow">AETHER STUDIO / VIDEO PUBLISHING</p><h2>From original file<br>to opening night.</h2><p>Upload your master once. Cloudflare prepares multiple qualities and streams the right quality for each viewer’s connection.</p><ol class="publishing-steps"><li><strong>01 / Upload</strong><span>Resumable, direct transfer</span></li><li><strong>02 / Encode</strong><span>Multiple resolutions & bitrates</span></li><li><strong>03 / Review</strong><span>Adaptive playback preview</span></li><li><strong>04 / Publish</strong><span>HLS / DASH through the CDN</span></li></ol><div class="notice" id="videoStatus" role="status">Connecting to the protected publishing service…</div><fieldset id="videoFields" disabled><form id="videoDraft" class="admin-form"><h3>1. Create a content draft</h3><label>Title<input name="name" required maxlength="120" placeholder="Movie or episode title"></label><label>Genre<select name="genre"><option>Drama</option><option>Adventure</option><option>Documentary</option><option>Sci-Fi</option><option>Thriller</option></select></label><label>Synopsis<textarea name="synopsis" required maxlength="5000" rows="3"></textarea></label><button class="button button-secondary" type="submit">Create live draft</button></form><div class="state-panel"><h3>2. Upload an original video</h3><label>Content draft<select id="videoTitle"><option value="">Select a title</option></select></label><label>Original video<input type="file" id="videoFile" accept="video/*,.mkv,.mov,.mp4,.webm"></label><label>Maximum duration (minutes)<input id="videoDuration" type="number" min="1" max="360" value="180"></label><p class="muted">Up to 30 GB · Uploads directly to Cloudflare · Keep this window open to pause and resume. Cloudflare storage and delivery charges apply.</p><progress id="videoProgress" max="100" value="0" aria-label="Upload progress"></progress><p id="videoBytes" class="muted" aria-live="polite">No upload in progress</p><div class="video-actions"><button class="button button-primary" id="videoUpload" type="button">Upload original</button><button class="button button-secondary" id="videoPause" type="button" disabled>Pause</button><button class="button button-secondary" id="videoResume" type="button" disabled>Resume</button></div></div><div class="state-panel"><h3>3. Encoding & delivery</h3><p id="encodingStatus" class="muted" role="status">Select an uploaded title to check its processing status.</p><div class="video-actions"><button class="button button-secondary" id="videoRefresh" type="button">Refresh processing</button><button class="button button-secondary" id="videoPreview" type="button" disabled>Preview adaptive playback</button></div><div id="adaptivePreview"></div><p class="muted">The player automatically selects bitrate and resolution, and buffers video segments as network conditions change. Available quality depends on the original video.</p></div><div class="state-panel"><h3>4. Review & publish</h3><label class="llm-toggle"><input id="videoRights" type="checkbox"> I have reviewed distribution rights.</label><label class="llm-toggle"><input id="videoCaptions" type="checkbox"> I have reviewed captions and accessibility.</label><button class="button button-primary" id="videoPublish" type="button" disabled>Publish to catalog</button></div></fieldset></div>`,'Video publishing studio');
 const root=document.querySelector('.video-studio'),get=id=>root.querySelector('#'+id),status=get('videoStatus'),select=get('videoTitle');
 let uploader=null,ready=false,titles=[],active=false,configured=false,closed=false;onCleanup?.(()=>{closed=true;uploader?.pause();root.querySelector('iframe')?.remove();});
 const show=message=>{if(root.isConnected)status.textContent=message;};
 const current=()=>{if(!select.value)throw new Error('Select a content draft first.');return select.value;};
 const review=()=>{get('videoPublish').disabled=!ready||!get('videoRights').checked||!get('videoCaptions').checked;};
 const list=async()=>{
  const data=await apiRequest('/api/admin/videos');if(!root.isConnected)return;
  configured=data.configured;const selected=select.value;titles=data.titles;select.replaceChildren(new Option('Select a title',''));
  for(const title of titles)select.add(new Option(`${title.name} · ${title.status} · ${title.media_state}`,title.id));
  if(titles.some(t=>t.id===selected))select.value=selected;
  get('videoFields').disabled=false;get('videoUpload').disabled=!data.configured;
  show(data.configured?'Connected to Cloudflare Stream. Select or create a draft to begin.':'Cloudflare Stream is not configured. Drafts are available; uploads require the server’s Cloudflare account and Stream token.');
 };
 const action=fn=>async()=>{if(active)return;active=true;try{await fn();}catch(error){show(error.message);}finally{active=false;}};
 const run=async()=>{
  get('videoPause').disabled=false;get('videoResume').disabled=true;
  try{if(await uploader.run()){show('Upload complete. Cloudflare is encoding your video. Refresh processing to check available renditions.');get('videoResume').disabled=true;}}
  catch(error){show(error.message);get('videoResume').disabled=false;}
  finally{get('videoPause').disabled=true;select.disabled=get('videoProgress').value<100;}
 };
 select.onchange=()=>{uploader=null;get('videoFile').disabled=false;get('videoResume').disabled=true;get('videoUpload').disabled=!configured||titles.find(t=>t.id===select.value)?.status!=='draft';ready=false;get('videoPreview').disabled=true;get('adaptivePreview').replaceChildren();review();get('encodingStatus').textContent='Refresh processing to verify this video with Cloudflare.';};
 get('videoDraft').onsubmit=async e=>{e.preventDefault();await action(async()=>{if(uploader&&get('videoProgress').value<100)throw new Error('Finish the current upload before creating another draft.');const result=await apiRequest('/api/admin/titles',{method:'POST',body:JSON.stringify(Object.fromEntries(new FormData(e.target)))});await list();select.value=result.id;e.target.reset();show('Draft created. Select its original video to upload.');})();};
 get('videoUpload').onclick=action(async()=>{
  const id=current(),file=get('videoFile').files[0],minutes=Number(get('videoDuration').value);
  if(!file||!file.size||file.size>30000000000)throw new Error('Choose a video between 1 byte and 30 GB.');
  if(!Number.isInteger(minutes)||minutes<1||minutes>360)throw new Error('Set a maximum duration from 1 to 360 minutes.');
  if(uploader?.running)throw new Error('Pause the current upload first.');
  get('videoUpload').disabled=true;show('Reserving a protected upload…');
  let upload;try{upload=await apiRequest(`/api/admin/videos/${id}/upload`,{method:'POST',body:JSON.stringify({bytes:file.size,maxDurationSeconds:minutes*60})});}catch(error){get('videoUpload').disabled=false;throw error;}
  if(closed||!root.isConnected)return;
  uploader=new ResumableVideoUpload(file,upload.uploadUrl,(sent,total)=>{if(!root.isConnected)return;get('videoProgress').value=sent/total*100;get('videoBytes').textContent=`${(sent/1e9).toFixed(2)} / ${(total/1e9).toFixed(2)} GB · ${Math.floor(sent/total*100)}%`;});
  select.disabled=true;get('videoFile').disabled=true;show('Uploading directly to Cloudflare. You can pause and resume here.');await run();
 });
 get('videoPause').onclick=()=>uploader?.pause();get('videoResume').onclick=action(run);
 get('videoRefresh').onclick=action(async()=>{const state=await apiRequest(`/api/admin/videos/${current()}/refresh`,{method:'POST'});ready=state.ready&&state.percent>=100;get('encodingStatus').textContent=`Cloudflare: ${state.state} · Encoding ${Math.floor(state.percent)}% · ${state.ready?'Playable':'Not playable yet'}`;get('videoPreview').disabled=!state.ready;review();show(ready?'All renditions are ready. Preview the video and complete the review.':'Processing status updated. Refresh again after encoding progresses.');});
 get('videoPreview').onclick=action(async()=>{const result=await apiRequest(`/api/admin/videos/${current()}/preview`,{method:'POST'});const url=new URL(result.iframe);if(url.protocol!=='https:'||!/^customer-[a-zA-Z0-9-]+\.cloudflarestream\.com$/.test(url.hostname))throw new Error('Invalid playback address');const frame=document.createElement('iframe');frame.src=url.href;frame.title='Protected adaptive video preview';frame.allow='autoplay; encrypted-media; picture-in-picture; fullscreen';frame.allowFullscreen=true;frame.referrerPolicy='no-referrer';get('adaptivePreview').replaceChildren(frame);show('Protected preview ready. Use the player’s quality controls or leave quality on Auto.');});
 get('videoRights').onchange=get('videoCaptions').onchange=review;
 get('videoPublish').onclick=action(async()=>{await apiRequest(`/api/admin/videos/${current()}/publish`,{method:'POST',body:JSON.stringify({rightsReviewed:get('videoRights').checked,captionsReviewed:get('videoCaptions').checked})});ready=false;review();await list();toast('Video published');show('Published. Subscribed viewers can request protected CDN playback.');});
 try{await list();}catch(error){show(error.message+' Upload and publishing controls are disabled.');}
}
