import {test} from 'node:test';
import assert from 'node:assert/strict';
import {canAdvanceStudio} from '../dist/studio-wizard.js';
const episode={id:'episode',kind:'episode',series_id:'series',season_number:1,episode_number:2};
test('details cannot advance without a persisted title or complete episode metadata',()=>{assert.equal(canAdvanceStudio({step:0}),false);assert.equal(canAdvanceStudio({step:0,title:{kind:'episode'}}),false);assert.equal(canAdvanceStudio({step:0,title:episode}),true);});
test('upload and review gates reject incomplete or busy states',()=>{assert.equal(canAdvanceStudio({step:1,title:episode}),false);assert.equal(canAdvanceStudio({step:1,title:episode,uploaded:true}),true);assert.equal(canAdvanceStudio({step:2,title:episode,reviews:false}),false);assert.equal(canAdvanceStudio({step:2,title:episode,reviews:true}),true);assert.equal(canAdvanceStudio({step:2,title:episode,reviews:true,busy:true}),false);});
