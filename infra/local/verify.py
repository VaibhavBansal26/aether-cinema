"""Real HTTP → JWT validation → Spring → PostgreSQL/Redis/Python integration checks."""
import json,os,time
from urllib.request import Request,urlopen
from urllib.error import HTTPError,URLError
BASE=os.environ.get('BASE_URL','http://api:8080')
MODE=os.environ.get('CHECK_MODE','normal')
PROFILE='20000000-0000-0000-0000-000000000001'
TITLE='30000000-0000-0000-0000-000000000001'
def call(path,subject='viewer',method='GET',body=None,expected=200):
    token=json.load(urlopen('http://oidc:8081/token?subject='+subject))['access_token'] if subject else None
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=Request(BASE+path,headers=headers,method=method,data=None if body is None else json.dumps(body).encode())
    try:
        with urlopen(req,timeout=15) as r:status=r.status;raw=r.read()
    except HTTPError as e:status=e.code;raw=e.read()
    assert status==expected,f'{method} {path}: expected {expected}, got {status}'
    return json.loads(raw) if raw else None
for attempt in range(90):
    try:
        with urlopen(BASE+'/actuator/health/readiness',timeout=2) as r:
            if r.status==200:break
    except (URLError,TimeoutError):pass
    time.sleep(1)
else:raise RuntimeError('API failed readiness')
call('/api/catalog',subject=None,expected=401)
call('/api/catalog',expected=200)
call('/api/profiles/'+PROFILE+'/progress',subject='other',expected=404)
call('/api/admin/recommendation-settings',expected=403)
studio=call('/api/admin/videos',subject='admin')
assert studio['configured'] is False
call('/api/admin/videos',expected=403)
draft=call('/api/admin/titles',subject='admin',method='POST',body={'name':'Pipeline check','synopsis':'Disposable integration draft','genre':'Drama'},expected=201)
call('/api/admin/videos/'+draft['id']+'/upload',subject='admin',method='POST',body={'bytes':5000000000,'maxDurationSeconds':7200},expected=409)
policy=call('/api/admin/recommendation-settings',subject='admin')
assert policy['enabled'] is False,'Fixture must start with LLM disabled'
call('/api/profiles/'+PROFILE+'/progress/'+TITLE,method='PUT',body={'position':25,'duration':100,'volume':.4,'muted':True},expected=204)
progress=call('/api/profiles/'+PROFILE+'/progress')
assert progress[0]['position_seconds']==25 and progress[0]['muted'] is True
call('/api/profiles/'+PROFILE+'/reactions/'+TITLE,method='PUT',body={'liked':True,'saved':True},expected=204)
plan=call('/api/profiles/'+PROFILE+'/recommendations')
if MODE=='python-down':
    assert plan['model']=='editorial-fallback'
else:
    assert plan['model']=='tfidf-v1' and plan['llm_status']=='disabled'
    assert plan['rows'][0]['id']=='continue'
    assert call('/api/profiles/'+PROFILE+'/recommendations')==plan
print(json.dumps({'result':'passed','mode':MODE,'model':plan['model'],'llm_enabled':False,'checks':['JWT required','profile isolation','admin isolation','progress and sound resume','reactions','recommendation response','admin video listing','unconfigured uploads safely blocked']},indent=2))
