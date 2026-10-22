/** Load the identity SDK only on a backend deployment that actually enables sign-in. */
let authModule=null,initialization=null;
export function initializeAuth(){
 if(!initialization)initialization=(async()=>{
  try{const response=await fetch('/api/auth/config',{cache:'no-store'});if(!response.ok)return false;
   const config=await response.json();if(!config.enabled)return false;
   authModule=await import('./auth-client.js');return await authModule.initializeAuth();
  }catch{return false;}
 })();return initialization;
}
export async function openAccountControls(options){
 await initializeAuth();
 if(authModule)return authModule.openAccountControls(options);
 options.modal('<div class="dialog-padding"><p class="eyebrow">YOUR AETHER ACCOUNT</p><h2>Sign-in is not connected here.</h2><p class="notice">This static preview keeps your library on this browser. Account sign-in is available on the backend deployment once its identity service is configured.</p></div>','Aether account');
}
