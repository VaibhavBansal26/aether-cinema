import {createAuthClient} from '@neondatabase/auth';
import {BetterAuthVanillaAdapter} from '@neondatabase/auth/vanilla/adapters';
import {configureSession,clearSession} from './api-client.js';
import {catalog} from './catalog.js';
let client=null,account=null,refreshTimer=null;
export const currentAccount=()=>account;
const escape=s=>String(s??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
/** Restore the first-party identity, then publish a complete account state before the session event. */
async function connect(){
 const tokenResponse=await fetch('/api/identity/token',{credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(20000)});
 const data=tokenResponse.ok?await tokenResponse.json():null;if(!data?.token)throw new Error('Your sign-in session could not be restored. Please sign in again.');
 const response=await fetch('/api/session/bootstrap',{method:'POST',headers:{Authorization:`Bearer ${data.token}`},cache:'no-store',signal:AbortSignal.timeout(20000)});
 if(!response.ok)throw new Error('You signed in, but your Aether account could not load. Please retry.');
 const result=await response.json(),profile=result.profiles[0];if(!profile)throw new Error('No profile is available.');
 const identityResponse=await fetch('/api/identity/get-session',{credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(20000)});
 const identity=identityResponse.ok?await identityResponse.json():null;
 account={...result.user,email:identity?.user?.email||'',name:identity?.user?.name||'My account'};
 const titleIds={};for(const title of result.titles){const local=catalog.find(x=>x.title===title.name);if(local)titleIds[local.id]=title.id;}
 configureSession({accessToken:data.token,profileId:profile.id,titleIds});
 clearTimeout(refreshTimer);refreshTimer=setTimeout(()=>connect().catch(()=>{account=null;clearSession();}),10*60*1000);
 return result;
}
export async function initializeAuth(){
 try{
  const response=await fetch('/api/auth/config',{cache:'no-store',signal:AbortSignal.timeout(20000)});if(!response.ok)return false;const config=await response.json();
  if(!config.enabled||!config.authUrl)return false;
  client=createAuthClient(new URL(config.authUrl,window.location.origin).href,{adapter:BetterAuthVanillaAdapter({fetchOptions:{credentials:'include'}})});
  try{await connect();}catch{account=null;clearSession();}
  return true;
 }catch{return false;}
}
/** Separate signed-out modes from the signed-in account view; credentials are cleared after success. */
export async function openAccountControls({modal,toast}){
 const options={modal,toast};
 if(account){
  modal(`<div class="dialog-padding"><p class="eyebrow">YOUR AETHER ACCOUNT</p><h2>You’re signed in.</h2><p class="notice" id="accountStatus" role="status">Signed in as <strong>${escape(account.email||account.name)}</strong></p><p>${account.role==='admin'?'Administrator · Creator studio access enabled':'Viewer account'}</p>${account.role==='admin'?'<button class="button button-primary" id="accountStudio" type="button">Open creator studio</button>':''}<button class="button button-secondary" id="accountSignout" type="button">Sign out</button><p class="muted">Your account stays signed in when you return to this browser.</p></div>`,'Your signed-in Aether account');
  document.querySelector('#accountStudio')?.addEventListener('click',()=>document.querySelector('#admin').click());
  document.querySelector('#accountSignout').onclick=async e=>{e.target.disabled=true;try{const result=await client.signOut();if(result.error)throw new Error('Sign-out failed. Please retry.');account=null;clearTimeout(refreshTimer);clearSession();toast('You are signed out');await openAccountControls(options);}catch(error){document.querySelector('#accountStatus').textContent=error.message;e.target.disabled=false;}};
  return;
 }
 modal(`<div class="dialog-padding"><p class="eyebrow">YOUR AETHER ACCOUNT · SIGNED OUT</p><h2 id="accountHeading">Sign in to Aether.</h2><div class="video-actions auth-modes"><button class="button button-primary" id="signinMode" aria-pressed="true" type="button">Sign in</button><button class="button button-secondary" id="signupMode" aria-pressed="false" type="button">Create account</button></div><p id="accountStatus" class="notice" role="status">Already have an account? Sign in with your email and password.</p><form id="accountForm" class="admin-form"><fieldset id="accountFields" ${client?'':'disabled'}><label id="accountNameLabel" hidden>Name<input id="accountName" maxlength="80" autocomplete="name"></label><label>Email<input id="accountEmail" type="email" required autocomplete="email"></label><label>Password<input id="accountPassword" type="password" required maxlength="128" autocomplete="current-password"></label><p id="passwordHint" class="muted" hidden>Use at least 12 characters for your new password.</p><button id="accountSubmit" class="button button-primary" type="submit">Sign in</button></fieldset></form></div>`,'Sign in to Aether');
 const form=document.querySelector('#accountForm'),status=document.querySelector('#accountStatus'),fields=document.querySelector('#accountFields');let signup=false;
 const mode=value=>{signup=value;document.querySelector('#accountHeading').textContent=value?'Create your Aether account.':'Sign in to Aether.';document.querySelector('#accountNameLabel').hidden=!value;document.querySelector('#accountName').required=value;const password=document.querySelector('#accountPassword');password.minLength=value?12:1;password.autocomplete=value?'new-password':'current-password';document.querySelector('#passwordHint').hidden=!value;document.querySelector('#accountSubmit').textContent=value?'Create account':'Sign in';document.querySelector('#signinMode').setAttribute('aria-pressed',String(!value));document.querySelector('#signupMode').setAttribute('aria-pressed',String(value));status.textContent=value?'New here? Create an account to save your profile.':'Already have an account? Sign in with your email and password.';};
 document.querySelector('#signinMode').onclick=()=>mode(false);document.querySelector('#signupMode').onclick=()=>mode(true);
 form.onsubmit=async e=>{e.preventDefault();if(!client||!form.reportValidity())return;fields.disabled=true;document.querySelector('#signinMode').disabled=true;document.querySelector('#signupMode').disabled=true;status.textContent=signup?'Creating your account…':'Signing you in…';
  try{const email=document.querySelector('#accountEmail').value.trim(),password=document.querySelector('#accountPassword').value,name=document.querySelector('#accountName').value.trim();
   const result=signup?await client.signUp.email({email,password,name}):await client.signIn.email({email,password});
   if(result.error){const message=result.error.message||'Sign-in failed.';throw new Error(signup&&/already.exists|already.registered|USER_ALREADY/i.test(message+' '+result.error.code)?'An account already exists for this email. Choose Sign in.':message);}await connect();if(!form.isConnected)return;form.reset();toast(signup?'Account created. You are signed in.':'You are signed in');await openAccountControls(options);
  }catch(error){if(form.isConnected){status.textContent=error.message;fields.disabled=false;document.querySelector('#signinMode').disabled=false;document.querySelector('#signupMode').disabled=false;}}
 };
 mode(false);
}
