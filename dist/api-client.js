/** Production OIDC bootstrap supplies credentials in memory. No token or LLM policy is stored locally. */
let session=null;
export function configureSession({accessToken,profileId,titleIds={}}){
 if(typeof accessToken!=='string'||!accessToken||!/^[0-9a-f-]{36}$/i.test(profileId))throw new Error('A verified OIDC session and profile UUID are required');
 if(Object.values(titleIds).some(id=>typeof id!=='string'||!/^[0-9a-f-]{36}$/i.test(id)))throw new Error('Title mappings must reference database UUIDs');
 session={accessToken,profileId,titleIds,reverse:Object.fromEntries(Object.entries(titleIds).map(([key,value])=>[value,key]))};
 window.dispatchEvent(new Event('aether:session-ready'));
}
export function clearSession(){session=null;window.dispatchEvent(new Event('aether:session-cleared'));}
export const profileId=()=>session?.profileId;
const remoteId=id=>session?.titleIds[id]||(/^[0-9a-f-]{36}$/i.test(id)?id:null);
export const hasSession=()=>session!==null;
export async function apiRequest(path,options={}){
 if(!session)throw new Error('Sign in to access your account.');
 if(!path.startsWith('/api/'))throw new Error('API requests must remain on the application origin');
 const response=await fetch(path,{...options,headers:{'Content-Type':'application/json',Authorization:`Bearer ${session.accessToken}`},cache:'no-store',signal:AbortSignal.timeout(20000)});
 if(!response.ok){if(response.status===401)throw new Error('Your session expired. Sign in again.');let detail;try{detail=await response.json();}catch{}if(typeof detail?.error==='string'&&detail.error.length<=250)throw new Error(detail.error);throw new Error(`API unavailable (${response.status}). No change was confirmed.`);}
 return response.status===204?null:response.json();
}
export const readRecommendationSettings=()=>apiRequest('/api/admin/recommendation-settings');
export const saveRecommendationSettings=policy=>apiRequest('/api/admin/recommendation-settings',{method:'PUT',body:JSON.stringify(policy)});
/** Translate the concept catalog IDs only through an explicit deployment-owned UUID mapping. */
export async function loadRecommendationLayout(){
 if(!session)return null;
 const result=await apiRequest(`/api/profiles/${session.profileId}/recommendations`);
 const id=x=>session?.reverse[x]||x;
 return {...result,hero:id(result.hero),rows:result.rows.map(row=>({...row,items:row.items.map(item=>({...item,title_id:id(item.title_id)}))}))};
}
export async function saveRemoteProgress(localId,progress){
 if(!session||!remoteId(localId))return;
 await apiRequest(`/api/profiles/${session.profileId}/progress/${remoteId(localId)}`,{method:'PUT',body:JSON.stringify(progress)});
}
export async function saveRemoteReaction(localId,reaction){
 if(!session||!remoteId(localId))return;
 await apiRequest(`/api/profiles/${session.profileId}/reactions/${remoteId(localId)}`,{method:'PUT',body:JSON.stringify(reaction)});
}
