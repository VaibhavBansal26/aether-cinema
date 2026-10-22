import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
test('series Watch routes to episodes without opening an unplayable collection',()=>{
 const source=readFileSync(new URL('../dist/app.js',import.meta.url),'utf8');const start=source.indexOf('function livePlay(');const end=source.indexOf('\n/**',start);const fn=source.slice(start,end);let routed=null;
 vm.runInNewContext(fn+';livePlay({id:"series-id",type:"series"});',{liveDetails:f=>routed=f,modal:()=>assert.fail('Series collections must not enter the media player')});assert.equal(routed.id,'series-id');
});
