import {test} from 'node:test';import assert from 'node:assert/strict';import {recommend,qualityFor,resumeAt} from '../dist/core.js';
test('low bandwidth and save-data cap startup quality',()=>{assert.equal(qualityFor(.5),360);assert.equal(qualityFor(100,true),360);assert.equal(qualityFor(undefined),480);assert.equal(qualityFor(8),1080);});
test('resume clamps invalid and out-of-range positions',()=>{assert.equal(resumeAt(200,100),99);assert.equal(resumeAt(-1,100),0);assert.equal(resumeAt(NaN,100),0);});
test('likes affect recommendations without mutating catalog',()=>{const c=[{id:'a',genre:'Drama',rating:8},{id:'b',genre:'Nature',rating:9}];assert.equal(recommend(c,['a'])[0].id,'a');assert.equal(recommend(c)[0].id,'b');assert.equal(c[0].id,'a');});
