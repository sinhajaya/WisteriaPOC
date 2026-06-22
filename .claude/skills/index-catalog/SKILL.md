---
name: index-catalog
description: Index the product catalog into PostgreSQL/pgvector, choosing the right attribute source and vision provider with safe fallbacks. Use when the user wants to (re)index the catalog or populate search.
disable-model-invocation: true
---

Index the catalog. First confirm the goal and pick the attribute source, then run.

1. **Choose the attribute source** (ask the user if unclear):
   - `CATALOG_ATTRIBUTE_SOURCE=labels` — uses the pre-labeled `data/catalog/labeled.jsonl`. **No API/VLM cost, fastest.** Prefer this if the file exists.
   - `CATALOG_ATTRIBUTE_SOURCE=extractor` — extracts attributes live. Requires a working vision backend:
     - `VISION_PROVIDER=vlm` → the VLM server (:5002) **must be reachable**, or indexing fails.
     - `VISION_PROVIDER=claude` → needs `ANTHROPIC_API_KEY` in `.env`.

2. **Run indexing** one of two ways:
   - Maven profile: `(cd services/api && ../../mvnw spring-boot:run -Dspring-boot.run.profiles=index)`
   - Or, if the API is already running, POST to `/api/v1/admin/index` (admin endpoint).

3. **Pre-flight check** when using `extractor` + `vlm`: confirm the VLM server is up (`curl http://localhost:5002/health`) before starting; otherwise switch to `labels` or `claude` and tell the user why.

4. Confirm `CATALOG_PRODUCTS_FILE` / `CATALOG_IMAGE_DIR` point at the intended catalog. Report how many products were indexed and any that failed.