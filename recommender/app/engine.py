"""Hybrid content/implicit-feedback ranking and homepage row/slot planning."""
from collections import Counter, defaultdict
from datetime import datetime, timezone
from functools import lru_cache
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer
from sklearn.preprocessing import normalize
from .models import RecommendationRequest
from .collaborative import CollaborativeModel
from .llm import OptionalReranker

@lru_cache(maxsize=8)
def content_features(documents: tuple[str,...]):
    """Learn a bounded TF-IDF vocabulary from catalog metadata; cache by exact catalog content."""
    return TfidfVectorizer(ngram_range=(1,2),max_features=4096,sublinear_tf=True,strip_accents="unicode",token_pattern=r"(?u)\b\w+\b").fit_transform(documents)

class RecommendationEngine:
    def __init__(self, collaborative=None, reranker=None):
        self.collaborative=collaborative or CollaborativeModel()
        self.reranker=reranker or OptionalReranker()

    def recommend(self, request: RecommendationRequest, now=None) -> dict:
        """Rank eligible supplied titles; derive row order, card positions, hero and explanations."""
        now=now or datetime.now(timezone.utc)
        titles=request.catalog
        if not titles:
            return {"hero":None,"rows":[],"llm_status":"disabled" if not request.policy.enabled else "not_needed","model":"tfidf-v1","personalized":False}
        ids=[t.id for t in titles]; index={id:i for i,id in enumerate(ids)}
        matrix=content_features(tuple(f"{t.genre} {t.genre} {t.genre} {t.kind} {t.name} {t.synopsis}" for t in titles))
        weights={}; completed=set(); continuing=[]; genre_weights=defaultdict(float); kind_weights=defaultdict(float)
        for event in request.interactions:
            if event.title_id not in index:
                continue
            fraction=event.position/event.duration
            age=max(0,(now-event.updated_at).total_seconds()/86400)
            freshness=2**(-age/30)
            watched=(0.25+fraction) if fraction>=0.05 or event.position>=60 else 0
            weight=(watched+1.5*event.liked+0.35*event.saved)*freshness
            if weight>0:
                weights[event.title_id]=weight
                title=titles[index[event.title_id]]
                genre_weights[title.genre]+=weight;kind_weights[title.kind]+=weight
            if fraction>=0.95:
                completed.add(event.title_id)
            elif event.position>0:
                continuing.append(event)
        vector=np.zeros(matrix.shape[1])
        for id,weight in weights.items():
            vector+=matrix[index[id]].toarray()[0]*weight
        if np.linalg.norm(vector):
            vector/=np.linalg.norm(vector)
        content=np.asarray(matrix @ vector).ravel()
        collab,has_collab=self.collaborative.scores(ids,weights)
        total_genre=max(genre_weights.values(),default=1); total_kind=max(kind_weights.values(),default=1)
        affinity=np.array([genre_weights[t.genre]/total_genre for t in titles])
        type_affinity=np.array([kind_weights[t.kind]/total_kind for t in titles])
        editorial=np.array([t.editorial_score for t in titles])
        scores=(.45*content+.22*affinity+.08*type_affinity+.25*editorial) if weights else editorial.copy()
        if has_collab:
            scores=.8*scores+.2*collab
        eligible=[i for i,id in enumerate(ids) if id not in completed]
        ordered=sorted(eligible,key=lambda i:(-scores[i],ids[i]))
        # Greedy MMR balances relevance with content novelty; stable IDs break ties.
        diverse=[]; pool=ordered[:60].copy(); tail=ordered[60:]
        local_index={i:n for n,i in enumerate(pool)}
        similarities=(matrix[pool] @ matrix[pool].T).toarray() if pool else np.zeros((0,0))
        while pool:
            def value(i):
                redundancy=max((float(similarities[local_index[i],local_index[j]]) for j in diverse[-4:]),default=0)
                return scores[i]-.12*redundancy
            chosen=min(pool,key=lambda i:(-value(i),ids[i]));diverse.append(chosen);pool.remove(chosen)
        diverse.extend(tail)
        candidates=diverse[:20]
        preferred={"genres":dict(sorted(genre_weights.items(),key=lambda p:-p[1])[:4]),"formats":dict(kind_weights)}
        llm_order,llm_status=self.reranker.rerank(request.policy,[{"id":ids[i],"name":titles[i].name,"genre":titles[i].genre,"kind":titles[i].kind,"synopsis":titles[i].synopsis[:200]} for i in candidates],preferred)
        if llm_order is not None:
            diverse=[index[id] for id in llm_order]+diverse[len(candidates):]
        rows=[]; occurrences=Counter()
        def add_row(key,name,indices,priority,reason,allow_repeat=False):
            kept=[i for i in indices if allow_repeat or occurrences[ids[i]]<2][:request.row_size]
            if not kept:
                return
            for i in kept:
                occurrences[ids[i]]+=1
            rows.append({"id":key,"title":name,"priority":priority,"reason":reason,"items":[{"title_id":ids[i],"position":n+1,"score":round(float(scores[i]),6),"reason":reason} for n,i in enumerate(kept)]})
        continuing.sort(key=lambda e:e.updated_at,reverse=True)
        add_row("continue","Continue watching",[index[e.title_id] for e in continuing],100,"Pick up where you left off",True)
        add_row("for_you","Picked for your taste" if weights else "Discover something great",diverse,90,"Based on your watched content and likes" if weights else "Editorial discovery for a new viewer")
        candidate_rows=[]
        for kind,label in [("films","Make it a movie night"),("series","Your next series")]:
            candidate_rows.append((50+20*kind_weights[kind]/total_kind,kind,label,[i for i in diverse if titles[i].kind==kind],"Your preferred format" if kind_weights[kind] else "Explore a different format"))
        for genre,weight in sorted(genre_weights.items(),key=lambda p:(-p[1],p[0]))[:2]:
            candidate_rows.append((55+20*weight/total_genre,"genre_"+genre,"More "+genre,[i for i in diverse if titles[i].genre==genre],"Because you watch "+genre))
        for priority,key,name,indices,reason in sorted(candidate_rows,key=lambda r:(-r[0],r[1])):
            add_row(key,name,indices,priority,reason)
        rows.sort(key=lambda r:-r.pop("priority"))
        for position,row in enumerate(rows,1):
            row["position"]=position
        return {"hero":ids[diverse[0]] if diverse else None,"rows":rows,"llm_status":llm_status,"model":"tfidf+implicit-svd-v1" if has_collab else "tfidf-v1","personalized":bool(weights)}
