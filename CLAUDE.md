# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Wisteria is a multi-service style-search POC: upload a furniture image, get visually/stylistically similar catalog products with explanations. AI extraction and explanations are **pluggable** — they swap between self-hosted models and Claude via env vars, with no code changes.

## Layout

Folders are grouped by role: `services/` (runnable services), `ml/` (offline pipelines), `data/` (inputs), `docs/` (HLD/LLD). New services go under `services/`.

| Dir | Stack | Port | Role |
|-----|-------|------|------|
| `services/api/` | Java 21, Spring Boot 4.1, Maven | 8081 | REST orchestration; calls CLIP/VLM, PostgreSQL (pgvector), Redis |
| `services/clip/` | FastAPI, open_clip ViT-L/14 (768-dim) | 5001 | Image embeddings; zero-shot furniture gate |
| `services/vlm/` | FastAPI, Qwen2.5-VL (+ optional LoRA) | 5002 | Style attribute extraction |
| `services/ui/` | React 18, Vite, TypeScript | 3000 | SPA (feature code under `src/features/`); proxies `/api`,`/static`,`/catalog` → :8081 |
| `ml/training/` | Python | — | Distills Claude labels into a Qwen LoRA adapter (Colab GPU) |
| `data/catalog/` | data | — | `products.json`, optional `labeled.jsonl`, `images/` (gitignored) |

## Commands (non-obvious only)

```bash
./run-app.sh                                              # loads .env, runs API via mvnw spring-boot:run
(cd services/api && ../../mvnw spring-boot:run -Dspring-boot.run.profiles=index)   # catalog indexing
docker compose up -d --build                             # full local stack (infra + all services)
docker compose logs -f clip                              # watch model download (~1.7GB first run)
docker compose down -v                                   # stop + wipe DB/Redis volumes
cd services/vlm && uvicorn main:app --port 5002          # VLM must run on HOST on Apple Silicon (no MPS in Docker)
cd ml/training && python run_pipeline.py --epochs 3 --serve  # label → train LoRA → eval → serve (+ngrok tunnel)
```
Standard `./mvnw clean package`, `npm run dev`/`npm run build`, `uvicorn main:app` work as expected per module.

## Env vars

Config lives in `.env` at the repo root (never commit it — it holds `ANTHROPIC_API_KEY`). Key switches:
- `VISION_PROVIDER` — `vlm` (default, self-hosted) or `claude`
- `VLM_SERVER_URL` / `CLIP_SERVER_URL` — service endpoints (Docker overrides with service names)
- `CATALOG_ATTRIBUTE_SOURCE` — `extractor` (live VLM/Claude) or `labels` (pre-labeled `labeled.jsonl`, no API cost)
- `ANTHROPIC_MODEL_EXTRACT` / `ANTHROPIC_MODEL_EXPLAIN`, `POSTGRES_*`, `REDIS_HOST`, `CATALOG_PRODUCTS_FILE`, `CATALOG_IMAGE_DIR`
- `FURNITURE_GATE_ENABLED` / `FURNITURE_GATE_THRESHOLD`

Full defaults are in `services/api/src/main/resources/application.yml`.

## Gotchas

- **Indexing needs the VLM** when `VISION_PROVIDER=vlm` — if the VLM server is down, indexing fails. Fallbacks: set `VISION_PROVIDER=claude` (needs `ANTHROPIC_API_KEY`) or `CATALOG_ATTRIBUTE_SOURCE=labels`.
- **DB schema is owned by Flyway** (`V1__…`, `V2`, `V3` migrations); Hibernate `ddl-auto=none`. Change schema via a new migration, not entities.
- **Apple Silicon**: run the VLM server on the host (Docker has no MPS access); CLIP falls back to MPS/CPU.
- **Graceful degradation**: missing AI → template fallback; missing Redis cache → slower but works; only a missing CLIP server is a hard fail.
- **Redis cache** keys on a DCT perceptual hash (pHash) of the image, so it survives restarts and minor image variation.
- **Catalog images are gitignored** — prod images come from S3; the wisteria.com scrape was POC-only. Catalog/S3 are JPEG/PNG only.

## Conventions

- No PR gate — commit directly to `main`.
- No formatter/linter is configured; match the style of surrounding code.
- The closed-vocabulary attribute enums in `services/vlm/main.py` mirror the Java tool schema (and `ml/training/taxonomy.py`) — keep them in sync if you change any one.