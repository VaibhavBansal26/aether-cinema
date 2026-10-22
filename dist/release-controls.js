/** Convert an explicit local/UTC editorial choice to an unambiguous server UTC instant. */
export function releaseSelection(root,now=Date.now()){
 const get=id=>root.querySelector('#'+id);
 if(get('releaseMode').value!=='scheduled')return {};
 const input=get('releaseAt').value;if(!input)throw new Error('Choose a release date and time.');
 const utc=get('releaseBasis').value==='UTC',date=new Date(input+(utc?'Z':''));
 if(!Number.isFinite(date.getTime())||date.getTime()<=now)throw new Error('Choose a future release date and time.');
 const parts=[date.getFullYear(),date.getMonth()+1,date.getDate(),date.getHours(),date.getMinutes()];
 if(!utc&&parts.map((v,i)=>i?v.toString().padStart(2,'0'):v).join('-')!==input.replace('T','-').replace(':','-'))throw new Error('That local time does not exist because of a clock change. Choose another time or UTC.');
 return {releaseAt:date.toISOString(),releaseTimeZone:utc?'UTC':Intl.DateTimeFormat().resolvedOptions().timeZone};
}
/** Shared schedule controls apply to both private R2 bundles and managed video publishing. */
export function mountReleaseControls(root){
 const get=id=>root.querySelector('#'+id),update=()=>{
  get('releaseDateFields').hidden=get('releaseMode').value!=='scheduled';get('videoReschedule').textContent=get('releaseMode').value==='scheduled'?'Update selected scheduled release':'Release selected scheduled title now';
  try{const plan=releaseSelection(root);get('releasePreview').textContent=plan.releaseAt?`Release: ${new Date(plan.releaseAt).toLocaleString()} (${Intl.DateTimeFormat().resolvedOptions().timeZone}) · ${plan.releaseAt} UTC instant. Videos stay private until then.`:'Release immediately after the media and publishing reviews pass.';}catch(error){get('releasePreview').textContent=error.message;}
 };
 for(const id of ['releaseMode','releaseAt','releaseBasis'])get(id).addEventListener('input',update);update();return ()=>releaseSelection(root);
}
