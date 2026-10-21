"""Offline implicit-feedback SVD. Artifacts contain numeric arrays and IDs, never pickled code."""
from pathlib import Path
import json
import numpy as np
from scipy.sparse import coo_matrix
from sklearn.decomposition import TruncatedSVD
from sklearn.preprocessing import normalize


def train(records: list[dict], output: Path, components: int = 32) -> dict:
    """Fit item factors from aggregated positive implicit interactions. IDs should be pseudonymous."""
    if not records or any(not isinstance(r.get("weight"), (int, float)) or not np.isfinite(r["weight"]) or r["weight"] <= 0 for r in records):
        raise ValueError("Training requires finite positive interaction weights")
    users = sorted({str(r["profile_id"]) for r in records})
    items = sorted({str(r["title_id"]) for r in records})
    if min(len(users), len(items)) < 3:
        raise ValueError("At least three profiles and titles are required")
    u, t = {x:i for i,x in enumerate(users)}, {x:i for i,x in enumerate(items)}
    matrix = coo_matrix(([np.log1p(r["weight"]) for r in records], ([u[str(r["profile_id"]) ] for r in records], [t[str(r["title_id"]) ] for r in records])), shape=(len(users),len(items))).tocsr()
    rank = min(components, min(matrix.shape)-1)
    model = TruncatedSVD(n_components=rank, random_state=42)
    model.fit(matrix)
    factors = normalize(model.components_.T)
    output.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(output, ids=np.array(items, dtype=str), factors=factors)
    report = {"model":"implicit-svd-v1", "profiles":len(users), "titles":len(items), "components":rank}
    output.with_suffix(".json").write_text(json.dumps(report, indent=2))
    return report


class CollaborativeModel:
    def __init__(self, path: str = ""):
        self.ids, self.factors = {}, None
        if path:
            with np.load(path, allow_pickle=False) as data:
                ids, factors = data["ids"].tolist(), data["factors"]
            if factors.ndim != 2 or factors.shape[0] != len(ids) or not np.isfinite(factors).all() or len(set(ids)) != len(ids):
                raise ValueError("Invalid collaborative artifact")
            self.ids, self.factors = {x:i for i,x in enumerate(ids)}, normalize(factors)

    def scores(self, title_ids: list[str], weights: dict[str,float]) -> tuple[np.ndarray,bool]:
        """Infer a viewer vector from known watched items; unseen items/profiles use content ranking."""
        output = np.zeros(len(title_ids))
        supported = [(self.ids[id],weight) for id,weight in weights.items() if id in self.ids and weight>0]
        if self.factors is None or not supported:
            return output, False
        vector = sum((self.factors[i]*w for i,w in supported), start=np.zeros(self.factors.shape[1]))
        norm = np.linalg.norm(vector)
        if not norm:
            return output, False
        for n,id in enumerate(title_ids):
            if id in self.ids:
                output[n] = max(0,float(self.factors[self.ids[id]] @ (vector/norm)))
        return output, True
