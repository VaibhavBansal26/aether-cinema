import {test} from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
test('replacing sample catalog tolerates stale sample save controls',()=>{
 const source=readFileSync(new URL('../dist/app.js',import.meta.url),'utf8');
 const fn=source.slice(source.indexOf('function syncCollection()'),source.indexOf('\nfunction toggleSaved'));
 const stale={dataset:{saveId:'signal'}};
 const hero={classList:{toggle(){}},setAttribute(){}};
 const title={id:'live-id',title:'Live series'};
 const context={catalog:[title],state:{saved:[]},featureId:'live-id',titleById:id=>id===title.id?title:undefined,$:id=>id==='#count'?{}:hero,$$:()=>[stale],html:x=>x,icon:()=>'',icons:()=>{}};
 vm.runInNewContext(fn+';syncCollection();',context);assert.equal(stale.disabled,true);
});
