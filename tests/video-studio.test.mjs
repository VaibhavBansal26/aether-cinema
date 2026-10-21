import test from 'node:test';
import assert from 'node:assert/strict';
import {uploadEndpoint,ResumableVideoUpload} from '../dist/video-studio.js';
test('uploads accept only trusted HTTPS provider destinations',()=>{
 assert.equal(uploadEndpoint('https://upload.videodelivery.net/video'),'https://upload.videodelivery.net/video');
 for(const value of ['http://upload.videodelivery.net/v','https://evil.example/v','https://upload.videodelivery.net.evil.example/v','https://user:pass@upload.videodelivery.net/v'])assert.throws(()=>uploadEndpoint(value));
});
test('a resumed upload begins at the provider committed offset',async()=>{
 const oldFetch=globalThis.fetch,oldXHR=globalThis.XMLHttpRequest;const ranges=[];
 globalThis.fetch=async()=>({ok:true,headers:new Headers({'Upload-Offset':'10'})});
 class XHR {upload={};open(){}setRequestHeader(){}getResponseHeader(){return '20';}send(){this.status=204;this.onload();}}
 globalThis.XMLHttpRequest=XHR;
 try{
  const upload=new ResumableVideoUpload({size:20,slice:(a,b)=>{ranges.push([a,b]);return new Blob(['test']);}},'https://upload.videodelivery.net/video',()=>{});
  assert.equal(await upload.run(),true);assert.deepEqual(ranges,[[10,20]]);
 }finally{globalThis.fetch=oldFetch;globalThis.XMLHttpRequest=oldXHR;}
});
