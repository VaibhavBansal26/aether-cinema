import test from 'node:test';
import assert from 'node:assert/strict';
import {releaseSelection} from '../dist/release-controls.js';
const root=values=>({querySelector:id=>({value:values[id.slice(1)]})});
test('UTC editorial schedules retain the intended exact instant',()=>{const value=releaseSelection(root({releaseMode:'scheduled',releaseAt:'2027-03-14T07:30',releaseBasis:'UTC'}),Date.parse('2027-01-01'));assert.equal(value.releaseAt,'2027-03-14T07:30:00.000Z');assert.equal(value.releaseTimeZone,'UTC');});
test('immediate publication and invalid release times are distinct',()=>{assert.deepEqual(releaseSelection(root({releaseMode:'now'})),{});assert.throws(()=>releaseSelection(root({releaseMode:'scheduled',releaseAt:'',releaseBasis:'UTC'})));assert.throws(()=>releaseSelection(root({releaseMode:'scheduled',releaseAt:'2020-01-01T00:00',releaseBasis:'UTC'})));});
