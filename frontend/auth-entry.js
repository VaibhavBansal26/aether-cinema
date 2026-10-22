import {createAuthClient} from '@neondatabase/auth';
import {BetterAuthVanillaAdapter} from '@neondatabase/auth/vanilla/adapters';
// External runtime modules keep one shared in-memory API session after bundling.
import {configureSession,clearSession} from './api-client.js';
import {catalog} from './catalog.js';
let client=null,account=null,refreshTimer=null;
export const currentAccount=()=>account;
async function connect(){
 const {data,error}=await client.token();if(error||!data?.token)throw new Error('Sign-in could not establish a session. Check browser cookie settings and try again.');
 const response=await fetch('/api/session/bootstrap',{method:'POST',headers:{Authorization:`Bearer ${data.token}`},cache:'no-store',signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw new Error('Your identity is signed in, but the application account could not be loaded.');
 const result=await response.json();const profile=result.profiles[0];if(!profile)throw new Error('No profile is available.');
 const titleIds={};for(const title of result.titles){const local=catalog.find(x=>x.title===title.name);if(local)titleIds[local.id]=title.id;}
 configureSession({accessToken:data.token,profileId:profile.id,titleIds});account=result.user;
 clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>connect().catch(()=>{account=null;clearSession();}),10*60*1000);
 return result;
}
export async function initializeAuth(){
 try{
  const response=await fetch('/api/auth/config',{cache:'no-store'});if(!response.ok)return false;const config=await response.json();
  if(!config.enabled||!config.authUrl)return false;
  client=createAuthClient(config.authUrl,{adapter:BetterAuthVanillaAdapter({fetchOptions:{credentials:'include'}})});
  const {data}=await client.getSession();if(data?.user)await connect();
  return true;
 }catch{return false;}
}
export async function openAccountControls({modal,toast}){
 modal(`<div class="dialog-padding"><p class="eyebrow">YOUR AETHER ACCOUNT</p><h2>A world of stories.<br>One place to return.</h2><p id="accountStatus" class="notice" role="status">${client?'Sign in to sync your profile and access your account.':'Sign-in is available on the deployed backend application. This static preview has no identity service connected.'}</p><form id="accountForm" class="admin-form"><fieldset id="accountFields" ${client?'':'disabled'}><label>Name (for a new account)<input id="accountName" maxlength="80" autocomplete="name"></label><label>Email<input id="accountEmail" type="email" required autocomplete="email"></label><label>Password<input id="accountPassword" type="password" required minlength="12" maxlength="128" autocomplete="current-password"></label><button class="button button-primary" type="submit">Sign in</button><button class="button button-secondary" type="button" id="accountSignup">Create account</button></fieldset></form><button class="button button-secondary" id="accountSignout" ${client?'':'disabled'} type="button">Sign out</button><p class="muted">Identity sessions are managed by Neon Auth. API access tokens stay in memory. Creating an account grants viewer access; admin roles are assigned separately by the operator.</p></div>`,'Sign in to Aether');
 const form=document.querySelector('#accountForm'),status=document.querySelector('#accountStatus'),fields=document.querySelector('#accountFields');
 if(account)status.textContent=`Signed in · ${account.role==='admin'?'Administrator':'Viewer'}`;
 const submit=async signup=>{
  if(!client||!form.reportValidity())return;fields.disabled=true;
  try{
   const email=document.querySelector('#accountEmail').value.trim(),password=document.querySelector('#accountPassword').value,name=document.querySelector('#accountName').value.trim();
   if(signup&&!name)throw new Error('Enter your name to create an account.');
   const result=signup?await client.signUp.email({email,password,name}):await client.signIn.email({email,password});
   if(result.error)throw new Error(result.error.message||'Sign-in failed.');
   await connect();if(!form.isConnected)return;
   document.querySelector('#accountPassword').value='';status.textContent=`Signed in · ${account.role==='admin'?'Administrator':'Viewer'}`;toast('Account connected');
  }catch(error){if(form.isConnected)status.textContent=error.message;}finally{fields.disabled=false;}
 };
 form.onsubmit=e=>{e.preventDefault();void submit(false);};document.querySelector('#accountSignup').onclick=()=>submit(true);
 document.querySelector('#accountSignout').onclick=async()=>{
  try{const result=await client.signOut();if(result.error)throw new Error('Sign-out could not be confirmed. Try again.');account=null;clearTimeout(refreshTimer);clearSession();status.textContent='Signed out.';toast('Signed out');}
  catch(error){status.textContent=error.message;}
 };
}
