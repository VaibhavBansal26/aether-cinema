"""Private FastAPI inference endpoint. Only the Spring Boot service holds the shared token."""
import hmac
import os
from fastapi import FastAPI, Header, HTTPException, Depends
from fastapi.responses import JSONResponse
from .models import RecommendationRequest
from .engine import RecommendationEngine
from .collaborative import CollaborativeModel
from .llm import OptionalReranker

def create_app(engine=None, service_token=None):
    """Inject engine/token in tests; deployment must supply a high-entropy internal credential."""
    token=service_token if service_token is not None else os.environ.get("RECOMMENDER_SERVICE_TOKEN","")
    if len(token)<32:
        raise RuntimeError("RECOMMENDER_SERVICE_TOKEN must contain at least 32 characters")
    model=engine or RecommendationEngine(CollaborativeModel(os.environ.get("COLLABORATIVE_MODEL_PATH","")),OptionalReranker(os.environ.get("OLLAMA_BASE_URL","http://127.0.0.1:11434")))
    api=FastAPI(title="Aether recommendation service",docs_url=None,redoc_url=None,openapi_url=None)
    @api.middleware("http")
    async def bounded_body(request,call_next):
        length=request.headers.get("content-length")
        if length and (not length.isdigit() or int(length)>2*1024*1024):
            return JSONResponse({"error":"Payload too large"},status_code=413)
        # Uvicorn/gateway must also enforce connection/body timeouts.
        if request.method=="POST":
            body=bytearray()
            async for chunk in request.stream():
                body.extend(chunk)
                if len(body)>2*1024*1024:
                    return JSONResponse({"error":"Payload too large"},status_code=413)
            request._body=bytes(body)
        return await call_next(request)
    def authorized(authorization: str=Header(default="")):
        if not hmac.compare_digest(authorization,"Bearer "+token):
            raise HTTPException(status_code=401,detail="Service authentication required")
    @api.get("/health")
    def health():
        return {"status":"ok","default_mode":"ml_only"}
    @api.post("/v1/recommendations",dependencies=[Depends(authorized)])
    def recommendations(payload:RecommendationRequest):
        return model.recommend(payload)
    return api
