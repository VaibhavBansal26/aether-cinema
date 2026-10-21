import http from 'k6/http';
import { check, sleep } from 'k6';
const stages = {
  smoke: [{duration:'20s',target:2}],
  baseline: [{duration:'1m',target:10},{duration:'3m',target:10},{duration:'30s',target:0}],
  stress: [{duration:'2m',target:50},{duration:'3m',target:100},{duration:'1m',target:0}],
};
if (!__ENV.BASE_URL || !__ENV.TOKEN || !__ENV.PROFILE_ID) throw new Error('Set BASE_URL, TOKEN and owned PROFILE_ID');
if (!stages[__ENV.STAGE || 'smoke']) throw new Error('Unknown STAGE');
if (__ENV.STAGE === 'stress' && __ENV.ALLOW_STRESS !== 'yes') throw new Error('Stress requires ALLOW_STRESS=yes');
export const options = { stages: stages[__ENV.STAGE || 'smoke'], thresholds: {
  http_req_failed: ['rate<0.01'], http_req_duration: ['p(95)<1000'], checks:['rate>0.99']
}};
export default function () {
  const params={headers:{Authorization:`Bearer ${__ENV.TOKEN}`}};
  check(http.get(`${__ENV.BASE_URL}/api/catalog?limit=20`,params),{'catalog 200':r=>r.status===200});
  check(http.get(`${__ENV.BASE_URL}/api/profiles/${__ENV.PROFILE_ID}/recommendations`,params),{'recommendations 200':r=>r.status===200});
  sleep(1);
}
