"""Disposable LOCAL TEST signer. Never deploy this service or its tokens publicly."""
import base64,json,time,re,hashlib
from http.server import BaseHTTPRequestHandler,HTTPServer
from urllib.parse import urlparse,parse_qs
from cryptography.hazmat.primitives.asymmetric import rsa,padding
from cryptography.hazmat.primitives import hashes
key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
def enc(value): return base64.urlsafe_b64encode(value).rstrip(b'=').decode()
def integer(value): return enc(value.to_bytes((value.bit_length()+7)//8,'big'))
public=key.public_key().public_numbers()
kid=hashlib.sha256(str(public.n).encode()).hexdigest()[:16]
jwk={'kty':'RSA','kid':kid,'use':'sig','alg':'RS256','n':integer(public.n),'e':integer(public.e)}
class Handler(BaseHTTPRequestHandler):
    def log_message(self,*args): pass
    def do_GET(self):
        parsed=urlparse(self.path)
        if parsed.path=='/jwks': result={'keys':[jwk]}
        elif parsed.path=='/health': result={'status':'ok','local_test_only':True}
        elif parsed.path=='/token':
            subject=parse_qs(parsed.query).get('subject',['viewer'])[0]
            if subject not in ('viewer','admin','other') and not re.fullmatch(r'load-(?:[1-9]|[1-9][0-9]|100)',subject):
                self.send_error(400);return
            now=int(time.time())
            header=enc(json.dumps({'alg':'RS256','kid':kid}).encode())
            payload=enc(json.dumps({'sub':subject,'iss':'http://oidc:8081','aud':['aether-api'],'iat':now,'exp':now+3600}).encode())
            data=(header+'.'+payload).encode()
            result={'access_token':data.decode()+'.'+enc(key.sign(data,padding.PKCS1v15(),hashes.SHA256()))}
        else:self.send_error(404);return
        body=json.dumps(result).encode();self.send_response(200)
        self.send_header('Content-Type','application/json');self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(body)
HTTPServer(('0.0.0.0',8081),Handler).serve_forever()
