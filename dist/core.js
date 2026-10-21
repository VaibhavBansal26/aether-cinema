/** Browser-only fallback: watch progress and likes influence genres. Python provides trained ML when connected. */
export function recommend(catalog, likes=[], progress={}) {
 const affinity={};
 for(const title of catalog){
  const state=progress[title.id],fraction=state?.duration>0?Math.min(1,(state.seconds||0)/state.duration):0;
  const signal=(likes.includes(title.id)?2:0)+(fraction>=.05?fraction:0);
  affinity[title.genre]=(affinity[title.genre]||0)+signal;
 }
 const score=title=>(affinity[title.genre]||0)+title.rating/10-(progress[title.id]?.completed?3:0);
 return [...catalog].sort((a,b)=>score(b)-score(a));
}
/** Conservative initial quality cap. HLS uses measured throughput and buffer thereafter. */
export function qualityFor(mbps, saveData=false){if(saveData)return 360;if(!Number.isFinite(mbps)||mbps<=0)return 480;return mbps<1?360:mbps<2.5?480:mbps<5?720:1080;}
/** Validate resume state and constrain it to the media timeline. */
export function resumeAt(seconds,duration){return Number.isFinite(seconds)&&seconds>=0?Math.min(seconds,Math.max(0,duration-1)):0;}
