# Aether Python recommendations

Python 3.12 / FastAPI / scikit-learn / NumPy / SciPy. **No LLM or API key is required.** The default admin policy is disabled. This service has no OpenAI SDK or OpenAI API integration.

## Run and test

```sh
python -m venv .venv
. .venv/bin/activate
pip install -r requirements-dev.txt
python -m pytest -q
```

Run from this `recommender/` directory. Generate a unique internal service credential of at least 32 characters through your secret manager; inject `RECOMMENDER_SERVICE_TOKEN` into this service and Spring Boot. It is an internal authentication secret, not an LLM/provider API key.

```sh
uvicorn app.main:create_app --factory --host 127.0.0.1 --port 8090
```

Build with `docker build -t aether-recommender:0.1.0 .`. The production image uses a non-root user, one worker and bounded BLAS thread counts. `compose.recommendations.yml` at repository root starts the private service, without publishing inference ports and without starting or downloading an LLM. Deploy behind private networking/TLS and connection/body timeouts. Health is `GET /health`; inference is authenticated `POST /v1/recommendations`.

## Ranking and homepage position

1. Spring Boot validates OIDC, resolves an application identity, checks profile ownership, and loads published catalog candidates plus that profile's progress/likes/saved flags from PostgreSQL.
2. The Python service fits/caches TF-IDF features over genre, format, name and synopsis. Watch completion ratio, meaningful starts, likes, saves and a 30-day recency half-life produce a taste vector. Timestamp/position are available signals, not proof of uninterrupted viewing.
3. Content similarity, genre/format affinity and editorial score produce the relevance score. If an offline collaborative SVD artifact exists and the viewer has known item interactions, its item-factor score is blended in.
4. MMR diversifies the first 60 candidates. Already-completed items are excluded from new discovery. Actual unfinished items create the first Continue watching row.
5. The service ranks movies, series and high-affinity genre shelves. Every row and card has an explicit one-based `position`. Titles appear in at most two rows (Continue watching included), and within-row duplicate IDs are forbidden by construction.
6. The optional LLM may reorder only an existing bounded shortlist after an admin opts in. Exact candidate permutations are required; invented/missing/duplicate IDs, provider errors, timeouts or oversized output revert to the ML order.

Cold start uses editorial ranking and diverse formats. An empty catalog returns no hero/rows. No synthetic collaborative model is shipped as trained production data. `python -m scripts.demo` produces an explicitly synthetic example without any external call.

## Optional collaborative model

Train offline from aggregated, de-identified positive events, JSONL with `profile_id`, `title_id`, `weight` (finite >0). Likes/watch engagement can be converted to weights by your data pipeline. Split data chronologically and evaluate held-out ranking before rollout.

```sh
python -m scripts.train_collaborative /secure/path/aggregates.jsonl models/items.npz --components 32
```

The trainer learns TruncatedSVD factors from a sparse profile/item matrix. Artifacts store only title IDs and numeric arrays in NPZ, loaded with `allow_pickle=False`; the report contains aggregate counts. Set `COLLABORATIVE_MODEL_PATH` to a trusted read-only artifact when starting the service. Model loading is startup-only. No profile identifiers are retained in the factor artifact. Training exports still need access controls and retention rules.

## Optional LLM — opt-in only

- Database migration seeds `{enabled:false, provider:'none', model:''}`.
- The admin panel reads and updates policy through Spring Boot; browser localStorage never controls it.
- Choose **Local Ollama**, specify an already-installed model, enable the checkbox and save as an authenticated admin. Nothing is downloaded or inferred on save.
- `OLLAMA_BASE_URL` comes from trusted deployment configuration only. Admins/viewers cannot supply URLs, preventing arbitrary outbound endpoints from the UI.
- With `enabled=false`, the adapter returns before HTTP work, even if a provider/model/environment endpoint exists. A test installs an HTTP transport that fails if contacted and proves zero calls.
- With enablement, only aggregate genre/format weights and candidate metadata go to the trusted Ollama instance, without raw watch history, profile ID, email or auth tokens.
- Disable in the admin panel to stop new requests using the LLM. An already-running inference may finish; cancellation of in-flight requests is not implemented. No LLM is used for content indexing, model training, cold-start metadata or background jobs while off.

Ollama is optional and is neither installed nor started by this project. The real model path was not exercised; enabled behavior is tested with a mocked local transport.

## Contracts and limitations

The private endpoint accepts a maximum of 500 candidate titles, 1,000 aggregated per-title interactions, a 2 MiB body, trusted policy and row size 2–20. Public callers must use Spring's `GET /api/profiles/{profileId}/recommendations` instead; never expose the shared token in browser code. Spring's candidate query currently chooses the 500 most recently published titles, so older watched seeds may not be present. Extend retrieval/indexing before a large production catalog. There is no cross-profile response cache.

This is a tested reference recommendation service, not a certified million-user deployment or a measured recommendation-quality result. Production needs event pipelines, consent/retention, catalog/rights filtering, model registry/versioning, precomputed candidate sets, multi-region caches, feature jobs, offline Recall/NDCG/diversity evaluation, online experiments and load tests. Avoid per-request refits and synchronous LLM calls on a million-user serving path; the small-catalog cache is only a baseline.

Primary references:
- https://scikit-learn.org/stable/modules/generated/sklearn.feature_extraction.text.TfidfVectorizer.html
- https://scikit-learn.org/stable/modules/generated/sklearn.decomposition.TruncatedSVD.html
- https://fastapi.tiangolo.com/deployment/docker/
- https://github.com/ollama/ollama/blob/main/docs/api.md
