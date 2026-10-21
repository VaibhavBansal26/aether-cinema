import {readRecommendationSettings,saveRecommendationSettings} from './api-client.js';
/** Live admin settings: never simulate enablement or save LLM policy into localStorage. */
export async function openRecommendationControls({modal,toast}){
 modal(`<div class="dialog-padding"><p class="eyebrow">AETHER STUDIO / RECOMMENDATIONS</p><h2>Machine learning first.<br>LLM only when you choose.</h2><p>Watch history and content similarity rank titles and homepage rows without an LLM or API key.</p><div class="notice" id="recommendationStatus" role="status">Loading protected settings…</div><form id="recommendationSettings" class="admin-form"><fieldset id="recommendationFields" disabled><label class="llm-toggle"><input type="checkbox" id="llmEnabled"> Enable optional LLM reranking</label><p class="muted">Off by default. Only an authorized admin can enable it. Turning it off prevents inference calls even if a model is installed.</p><label>Provider<select id="llmProvider"><option value="ollama">Local Ollama</option></select></label><label>Installed model<input id="llmModel" maxlength="120" pattern="[a-zA-Z0-9_./:\\-]*" placeholder="Enter the exact installed model name" autocomplete="off"></label><p class="muted">The trusted Ollama endpoint is configured on the server. No cloud API key is needed. Saving does not download a model or run an inference request.</p><button class="button button-primary" type="submit" id="saveRecommendationPolicy">Save recommendation policy</button></fieldset></form><div class="state-panel"><h3>What keeps working with the LLM off?</h3><p class="muted">Content similarity, watch-history affinity, collaborative filtering when a trained model is available, diverse recommendations, and automatic row/card placement.</p></div></div>`,'Recommendation engine settings');
 const status=document.querySelector('#recommendationStatus'),form=document.querySelector('#recommendationSettings');
 let policy;
 try{
  policy=await readRecommendationSettings();
  if(!form.isConnected)return;
  document.querySelector('#recommendationFields').disabled=false;
  const enabled=document.querySelector('#llmEnabled'),provider=document.querySelector('#llmProvider'),model=document.querySelector('#llmModel');
  enabled.checked=policy.enabled;model.value=policy.model;
  const sync=()=>{provider.disabled=!enabled.checked;model.disabled=!enabled.checked;model.required=enabled.checked;};sync();enabled.onchange=sync;
  status.textContent=`Live policy: ${policy.enabled?'LLM enabled for '+policy.model:'ML only — LLM disabled'}. ${policy.deployment_configured?'Private recommendation service configured.':'Private recommendation service is not configured; enablement is blocked.'}`;
  form.onsubmit=async e=>{e.preventDefault();const button=document.querySelector('#saveRecommendationPolicy');button.disabled=true;try{
   if(enabled.checked&&(!policy.deployment_configured||!model.value.trim()))throw new Error('Configure the private service and specify an installed model before enabling the LLM.');
   policy=await saveRecommendationSettings({enabled:enabled.checked,provider:enabled.checked?'ollama':policy.provider,model:model.value.trim(),revision:policy.revision});
   if(!form.isConnected)return;
   status.textContent=policy.enabled?`Saved: optional LLM enabled for ${policy.model}. ML remains the fallback.`:'Saved: LLM disabled. Recommendations use ML only.';
   toast('Recommendation policy saved');
  }catch(error){if(form.isConnected)status.textContent=error.message;}finally{if(button.isConnected)button.disabled=false;}};
 }catch(error){if(form.isConnected){status.textContent=error.message+' No LLM is used by the static preview. Default policy is off.';}}
}
