import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';

test('a reload restores the viewer from the first-party cookie without SDK session cache',async()=>{
 let configured=null;const calls=[];const source=(await readFile(new URL('../frontend/auth-entry.js',import.meta.url),'utf8')).replace(/^import .*;$/gm,'').replace(/export /g,'');
 const context=vm.createContext({createAuthClient:()=>({}),BetterAuthVanillaAdapter:()=>({}),configureSession:x=>configured=x,clearSession:()=>{},catalog:[],window:{location:{origin:'https://aether.example'}},URL,AbortSignal,setTimeout:()=>1,clearTimeout:()=>{},fetch:async(path,options)=>{
  calls.push({path,options});return {ok:true,json:async()=>path==='/api/auth/config'?{enabled:true,authUrl:'/api/identity'}:path==='/api/identity/token'?{token:'verified-token'}:{user:{role:'viewer'},profiles:[{id:'123e4567-e89b-12d3-a456-426614174000'}],titles:[]}};
 }});
 vm.runInContext(source,context);assert.equal(await context.initializeAuth(),true);assert.equal(configured.accessToken,'verified-token');assert.equal(vm.runInContext('currentAccount().role',context),'viewer');assert.equal(calls[1].options.credentials,'same-origin');assert.equal(calls[2].options.headers.Authorization,'Bearer verified-token');
});
