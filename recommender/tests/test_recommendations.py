from datetime import datetime, timezone
import json
import httpx
import numpy as np
import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError
from app.models import RecommendationRequest, Title, Interaction, LlmPolicy
from app.engine import RecommendationEngine
from app.llm import OptionalReranker
from app.main import create_app
from app.collaborative import train, CollaborativeModel

NOW=datetime(2026,10,21,tzinfo=timezone.utc)
CATALOG=[
 Title(id="space1",name="First Contact",synopsis="Astronauts explore an alien planet and mysterious signal",genre="Sci-Fi",editorial_score=.6),
 Title(id="space2",name="Distant Orbit",synopsis="Astronauts discover an alien signal in deep space",genre="Sci-Fi",editorial_score=.6),
 Title(id="space3",name="Star Chronicles",synopsis="An alien planet and space explorers",genre="Sci-Fi",kind="series",editorial_score=.6),
 Title(id="drama1",name="Home Again",synopsis="A family returns to a seaside village for a wedding",genre="Drama",editorial_score=.8),
 Title(id="drama2",name="Summer Lives",synopsis="Family and romance in a seaside village",genre="Drama",kind="series",editorial_score=.7),
 Title(id="nature",name="Forest Life",synopsis="Wild animals live in a tropical forest",genre="Documentary",editorial_score=.7)]

def watched(id="space1",**kwargs):
 return Interaction(title_id=id,position=100,duration=100,updated_at=NOW,**kwargs)

def flat(plan):
 return [i["title_id"] for row in plan["rows"] if row["id"]!="continue" for i in row["items"]]

def test_watched_genre_changes_title_ranking_and_row_order():
 engine=RecommendationEngine()
 space=engine.recommend(RecommendationRequest(catalog=CATALOG,interactions=[watched()]),NOW)
 drama=engine.recommend(RecommendationRequest(catalog=CATALOG,interactions=[watched("drama1")]),NOW)
 assert space["hero"] in {"space2","space3"}
 assert drama["hero"]=="drama2"
 assert "space1" not in flat(space)
 assert any(row["title"]=="More Sci-Fi" for row in space["rows"])
 assert all(row["position"]==n for n,row in enumerate(space["rows"],1))
 for row in space["rows"]:
  assert [i["position"] for i in row["items"]]==list(range(1,len(row["items"])+1))

def test_series_preference_changes_its_shelf_position():
 plan=RecommendationEngine().recommend(RecommendationRequest(catalog=CATALOG,interactions=[watched("space3")]),NOW)
 rows={r["id"]:r["position"] for r in plan["rows"]}
 assert rows["series"]<rows["films"]

def test_continue_watching_is_first_and_completion_never_returns_as_new():
 event=Interaction(title_id="space1",position=40,duration=100,updated_at=NOW)
 plan=RecommendationEngine().recommend(RecommendationRequest(catalog=CATALOG,interactions=[event,watched("drama1")]),NOW)
 assert plan["rows"][0]["id"]=="continue"
 assert plan["rows"][0]["items"][0]["title_id"]=="space1"
 assert "drama1" not in flat(plan)

def test_cold_start_and_empty_catalog_work_without_a_model_or_key():
 engine=RecommendationEngine()
 plan=engine.recommend(RecommendationRequest(catalog=CATALOG),NOW)
 assert not plan["personalized"] and plan["llm_status"]=="disabled"
 assert plan["hero"]=="drama1"
 assert engine.recommend(RecommendationRequest(catalog=[]))["rows"]==[]

def test_llm_disabled_makes_zero_network_calls_even_with_provider_model_configured():
 calls=[]
 def fail(request):
  calls.append(request)
  raise AssertionError("LLM was contacted while disabled")
 reranker=OptionalReranker(client=httpx.Client(transport=httpx.MockTransport(fail)))
 plan=RecommendationEngine(reranker=reranker).recommend(RecommendationRequest(catalog=CATALOG,policy=LlmPolicy(enabled=False,provider="ollama",model="local-model")))
 assert plan["llm_status"]=="disabled" and not calls

@pytest.mark.parametrize("response",[["invented"],["space2","space2"],[],"not-an-array"])
def test_llm_cannot_invent_duplicate_or_drop_candidates(response):
 client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={"message":{"content":json.dumps({"ordered_ids":response})}})))
 policy=LlmPolicy(enabled=True,provider="ollama",model="local-model")
 result,status=OptionalReranker(client=client).rerank(policy,[{"id":"space1"},{"id":"space2"}],{})
 assert result is None and status=="invalid_response"

def test_enabled_llm_valid_permutation_can_reorder_candidates():
 client=httpx.Client(transport=httpx.MockTransport(lambda request:httpx.Response(200,json={"message":{"content":'{"ordered_ids":["space2","space1"]}'}})))
 ids,status=OptionalReranker(client=client).rerank(LlmPolicy(enabled=True,provider="ollama",model="local-model"),[{"id":"space1"},{"id":"space2"}],{})
 assert status=="applied" and ids==["space2","space1"]

def test_llm_timeout_preserves_ml_recommendations():
 def timeout(request):raise httpx.ReadTimeout("Test timeout")
 engine=RecommendationEngine(reranker=OptionalReranker(client=httpx.Client(transport=httpx.MockTransport(timeout))))
 plan=engine.recommend(RecommendationRequest(catalog=CATALOG,policy=LlmPolicy(enabled=True,provider="ollama",model="test")))
 assert plan["hero"] and plan["llm_status"]=="unavailable_or_invalid"

def test_enabling_requires_explicit_provider_and_model():
 with pytest.raises(ValidationError):LlmPolicy(enabled=True)
 with pytest.raises(ValidationError):LlmPolicy(enabled=True,provider="ollama",model="")

def test_internal_auth_and_input_validation():
 client=TestClient(create_app(service_token="test-only-service-credential-123456789"))
 assert client.post("/v1/recommendations",json={"catalog":[]}).status_code==401
 headers={"Authorization":"Bearer test-only-service-credential-123456789"}
 assert client.post("/v1/recommendations",headers=headers,json={"catalog":[]}).status_code==200
 assert client.post("/v1/recommendations",headers=headers,json={"catalog":[CATALOG[0].model_dump(),CATALOG[0].model_dump()]}).status_code==422
 assert client.post("/v1/recommendations",headers=headers,content=b"x"*(2*1024*1024+1)).status_code==413

def test_nonfinite_and_impossible_progress_is_rejected():
 with pytest.raises(ValidationError):Interaction(title_id="space1",position=float("nan"))
 with pytest.raises(ValidationError):Interaction(title_id="space1",position=101,duration=100)

def test_collaborative_artifact_trains_and_loads_without_pickle(tmp_path):
 events=[{"profile_id":u,"title_id":t,"weight":w} for u,t,w in [("u1","space1",5),("u1","space2",4),("u2","space1",3),("u2","space2",5),("u3","drama1",4),("u3","drama2",5),("u4","drama1",5),("u4","drama2",4)]]
 path=tmp_path/"items.npz"
 report=train(events,path,2)
 scores,used=CollaborativeModel(str(path)).scores(["space2","drama2","new"],{"space1":1})
 assert report["profiles"]==4 and used and np.isfinite(scores).all()
 assert scores[0]>scores[1] and scores[2]==0
