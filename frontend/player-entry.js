import Hls from 'hls.js';
import {apiRequest,profileId} from './api-client.js';
/** Only the configured Cloudflare Stream HTTPS origin can receive playback capabilities. */
export function playbackUrl(value,allowedOrigin){const u=new URL(value);if(u.protocol!=='https:'||(!/^customer-[a-zA-Z0-9-]+\.cloudflarestream\.com$/.test(u.hostname)&&u.origin!==allowedOrigin)||u.username||u.password||u.port||(!u.pathname.endsWith('/manifest/video.m3u8')&&!u.pathname.endsWith('/master.m3u8')))throw new Error('Invalid playback address');return u;}
/** HLS.js uses measured throughput and buffer occupancy for adaptive bitrate; LLMs never participate. */
export function mountAdaptivePlayer({video,titleId,progress,status,onProgress}){
 let disposed=false,lease=null,timer=null,hls=null,sequence=0,latest=null,lastSave=0,nativeRefresh=0,pending=false;
 const snapshot=()=>Number.isFinite(video.duration)&&video.duration>0?{position:video.currentTime,duration:video.duration,volume:video.volume,muted:video.muted}:null;
 const stop=()=>{disposed=true;clearTimeout(timer);hls?.destroy();video.pause();video.removeAttribute('src');video.load();if(lease)void apiRequest(`/api/playback-sessions/${lease.sessionId}`,{method:'DELETE',keepalive:true}).catch(()=>{});};
 const fail=e=>{if(disposed)return;status.textContent=e.message||'Playback stopped. Reopen the video to try again.';stop();};
 const checkpoint=()=>{const p=snapshot();if(p)onProgress?.(p);return p;};
 const heartbeat=async()=>{
  if(disposed||!lease||pending)return;pending=true;
  try{
   const next=await apiRequest(`/api/playback-sessions/${lease.sessionId}/heartbeat`,{method:'POST',body:JSON.stringify({sequence:++sequence,progress:checkpoint()})});
   if(disposed)return;lease=next;latest=playbackUrl(next.hls,next.mediaOrigin);
   // Native HLS has no request hook. Refresh before the prior capability expires, preserving time/sound.
   if(!hls&&Date.now()-nativeRefresh>70000){const p=video.currentTime,playing=!video.paused;video.src=latest.href;video.addEventListener('loadedmetadata',()=>{video.currentTime=Math.min(p,Math.max(0,video.duration-1));if(playing)void video.play().catch(()=>{});},{once:true});nativeRefresh=Date.now();}
   timer=setTimeout(heartbeat,next.heartbeatSeconds*1000);
  }catch(e){fail(e);}finally{pending=false;}
 };
 video.volume=Number.isFinite(progress?.volume)?Math.max(0,Math.min(1,progress.volume)):.7;video.muted=!!progress?.muted;
 video.addEventListener('loadedmetadata',()=>{if(progress?.seconds>0)video.currentTime=Math.min(progress.seconds,Math.max(0,video.duration-1));},{once:true});
 video.addEventListener('timeupdate',()=>{if(Date.now()-lastSave>15000){lastSave=Date.now();checkpoint();}});
 video.addEventListener('error',()=>fail(new Error('The video could not be decoded or loaded. Try reopening it.')));
 void(async()=>{try{
  lease=await apiRequest(`/api/playback/${titleId}`,{method:'POST',body:JSON.stringify({profileId:profileId(),clientId:crypto.randomUUID()})});if(disposed){void apiRequest(`/api/playback-sessions/${lease.sessionId}`,{method:'DELETE'}).catch(()=>{});return;}
  latest=playbackUrl(lease.hls,lease.mediaOrigin);
  if(Hls.isSupported()){
   hls=new Hls({capLevelToPlayerSize:true,maxBufferLength:30,maxMaxBufferLength:60,backBufferLength:30,xhrSetup:(xhr,value)=>{const url=new URL(value);if(url.origin!==latest.origin)throw new Error('Unexpected media origin');const parts=url.pathname.split('/');parts[1]=latest.pathname.split('/')[1];url.pathname=parts.join('/');xhr.open('GET',url.href,true);}});
   hls.on(Hls.Events.ERROR,(_,data)=>{if(data.fatal)fail(new Error('Adaptive playback interrupted. Reopen the video to retry.'));});hls.loadSource(latest.href);hls.attachMedia(video);
   hls.on(Hls.Events.LEVEL_SWITCHED,(_,data)=>{const level=hls.levels[data.level];status.textContent=`Adaptive quality · ${level?.height||'Auto'}p · ${Math.round((level?.bitrate||0)/1000)} kbps`;});
  }else if(video.canPlayType('application/vnd.apple.mpegurl')){video.src=latest.href;nativeRefresh=Date.now();status.textContent='Adaptive HLS · Native player';}
  else throw new Error('This browser does not support adaptive video playback.');
  timer=setTimeout(heartbeat,lease.heartbeatSeconds*1000);status.textContent='Adaptive playback ready. Press play.';
 }catch(e){fail(e);}})();
 return()=>{checkpoint();stop();};
}
