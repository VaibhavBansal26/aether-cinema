"""Synthetic illustration only: no production viewer data or live LLM calls."""
import json
from datetime import datetime,timezone
from app.engine import RecommendationEngine
from app.models import RecommendationRequest

now=datetime.now(timezone.utc).isoformat()
catalog=[
 {"id":"signal","name":"The Signal","synopsis":"An astronaut discovers a mysterious alien transmission in space","genre":"Sci-Fi","editorial_score":.9},
 {"id":"orbit","name":"A World Beyond","synopsis":"Space explorers investigate a mysterious alien planet","genre":"Sci-Fi","kind":"series","editorial_score":.8},
 {"id":"stars","name":"When the Stars Fall","synopsis":"Scientists explore a transmission from a distant planet","genre":"Sci-Fi","editorial_score":.8},
 {"id":"coast","name":"Where We Belong","synopsis":"Family and romance in a small seaside village","genre":"Drama","editorial_score":.95},
 {"id":"tide","name":"The Last Tide","synopsis":"A detective uncovers secrets in a coastal town","genre":"Thriller","kind":"series","editorial_score":.85},
 {"id":"forest","name":"The Living Earth","synopsis":"Wildlife and animals of the forest","genre":"Documentary","kind":"series","editorial_score":.8}]
engine=RecommendationEngine()
result={"data":"synthetic demonstration; not a production quality evaluation","cold_start":engine.recommend(RecommendationRequest(catalog=catalog)),"after_watching_scifi":engine.recommend(RecommendationRequest(catalog=catalog,interactions=[{"title_id":"signal","position":100,"duration":100,"liked":True,"updated_at":now}]))}
print(json.dumps(result,indent=2))
