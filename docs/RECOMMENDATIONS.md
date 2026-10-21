# ML-first recommendation engine and optional admin-controlled LLM

The Python service implements content-based TF-IDF ranking, watch/like/format affinity, optional offline implicit-feedback TruncatedSVD, diversity reranking and homepage row/card positions. **LLM inference is disabled by default and never required for normal ML ranking.** The optional provider is a trusted local Ollama endpoint, not OpenAI. No OpenAI key was created, required or used for this implementation.

## Deployment and migration

1. Apply `server/schema.sql` only for a new database, then apply `server/migrations/002_recommendations.sql` once through a migration role. Existing deployments apply only migration 002. Do not edit/reapply the baseline or let the runtime own DDL.
2. Migration 002 adds title `kind` (`films` / `series`), `editorial_score` (0–1), reaction timestamps and the singleton persisted admin policy. Existing titles default to `films`; classify series explicitly. Set appropriate editorial scores during reviewed publishing. The policy starts disabled, provider `none`, blank model, revision 0.
3. Run the Python service as documented in `recommender/README.md`. Inject a new unique >=32-character `RECOMMENDER_SERVICE_TOKEN` into both services. Set Spring `RECOMMENDER_URL` to the private service origin. Leave `COLLABORATIVE_MODEL_PATH` empty until an offline trained artifact exists.
4. Configure a trusted local Ollama endpoint only if desired. **Do not enable it by environment alone.** No model download or inference happens as part of deployment or saving the admin policy.
5. Wire your OIDC login into the frontend `configureSession` export in `dist/api-client.js`. Pass the real access token, owned profile UUID, and deployment-owned UI-title→database-UUID mapping. Tokens remain in memory, not localStorage. The fictional preview catalog IDs are not real database UUIDs and are never silently sent as such.
6. Authenticated playback checkpoints and reactions are written to Spring Boot. On session start and after relevant activity, the browser fetches a server plan and renders its row/card positions for mapped titles. The current production-catalog/artwork adapter and live OIDC hosting are still deployment tasks; the static preview uses local fallback behavior.

## Admin panel

Creator studio → Manage recommendation settings. The panel loads protected live settings. Without an authenticated admin, the toggle, model and save controls remain disabled and no change is simulated. With verified admin access, specify Local Ollama and an installed model, explicitly enable, then save. Provider URLs/secrets are not accepted from browser input.

`GET /api/admin/recommendation-settings` returns enabled/provider/model/revision and deployment status. `PUT` accepts enabled/provider/model/revision. Missing provider/model while enabling returns 400. Missing service configuration returns 409. A server-owned viewer role returns 403. Optimistic revision checking rejects stale writes with 409. Every successful save and policy transition writes an audit row in the same transaction. Turning off does not erase the configured model; it prevents subsequent inference calls. Previously in-flight inference is not cancelled.

The browser never sends LLM policy on recommendation requests. Spring reads the persisted policy and supplies it to the private Python endpoint. Default-off is enforced in code and database, not only through a checkbox.

## Endpoint and response

`GET /api/profiles/{profileId}/recommendations`: requires Bearer JWT, provisioned user and profile ownership. Cache-Control no-store. Only published candidates enter the engine. Example plan:

```json
{"hero":"title-id","model":"tfidf-v1","personalized":true,"llm_status":"disabled","rows":[{"id":"for_you","position":1,"title":"Picked for your taste","reason":"Based on your watched content and likes","items":[{"title_id":"title-id","position":1,"score":0.81,"reason":"Based on your watched content and likes"}]}]}
```

Positions are **one-based** and scores are model relevance values, not probabilities, quality guarantees or watch-time predictions. Continue watching, when present, comes first. Movie/series/genre rows are ordered from profile affinity. Up to 20 shortlisted candidates can be LLM-reranked after opt-in; the LLM cannot create titles or alter eligibility.

If Python is down/unconfigured, Spring returns an explicitly labelled editorial fallback. If Ollama is unavailable, malformed or times out, Python returns its ML plan with a fallback status. A static browser without live login uses the lightweight watch/like-aware JavaScript fallback and makes no Python/LLM request.

## Function reference

| File / function | Purpose |
|---|---|
| `app/models.py`: contracts | Bound catalog/history and policy. Reject invalid timeline, timezone, nonfinite numbers, duplicate IDs, invalid opt-in. |
| `app/engine.py`: `content_features` | Fit/cache a bounded TF-IDF vocabulary from metadata without external calls. |
| `RecommendationEngine.recommend` | Aggregate history, score items, diversify, exclude completed titles, plan hero/rows/positions and reasons. |
| `app/collaborative.py`: `train` | Learn sparse implicit-feedback SVD item factors offline; save safe NPZ and aggregate report. |
| `CollaborativeModel.scores` | Infer viewer factors from known items; blend collaborative affinity only when supported. |
| `app/llm.py`: `OptionalReranker.rerank` | Early exit while off; bounded local inference after opt-in; validate exact allowed ID permutation and fall back. |
| `app/main.py`: `create_app` | Require service token, bound request size, expose private authenticated inference and health. |
| `scripts/train_collaborative.py` | CLI entry for trusted offline aggregated training exports. |
| `scripts/demo.py` | Explicitly synthetic cold-start/watch-history example with LLM off. |
| `RecommendationSettings` | Java Bean Validation for optional provider/model and expected revision. |
| `RecommendationGateway.adminSettings`, `saveSettings` | Verify admin role; read/transactionally update policy and audit. |
| `RecommendationGateway.recommendations` | Verify profile ownership; load bounded published candidates and profile signals; call Python; serve editorial fallback. |
| `RecommendationController` | Authenticated public recommendation/settings routes; no direct untrusted policy forwarding. |
| `dist/api-client.js` | In-memory OIDC adapter, same-origin authenticated API calls, UUID mapping, progress/reaction sync and layout requests. |
| `dist/recommendation-controls.js`: `openRecommendationControls` | Protected panel, explicit opt-in, model selection, revisioned save; no local fake policy. |
| `dist/app.js`: `refreshRecommendations` | Fetch current plan after account activity, ignore out-of-order responses, apply published row/card positions to mapped catalog titles. |

## Verification

See `VALIDATION.md` for results. Python tests cover default-off zero HTTP calls, watch-dependent ranking and row placement, complete-title filtering, optional collaborative training/loading, unauthorized inference, invalid payloads and mocked enabled-LLM success/fallback. Java tests cover admin-only access, explicit model/provider validation, profile ownership, default-off persistence, stale revision rejection, and a real PostgreSQL→Spring→Python inference round trip.

Synthetic fixtures prove functional behavior only. No production viewer dataset, model quality benchmark, running local LLM, public backend host or million-user capacity was provisioned by this change.
