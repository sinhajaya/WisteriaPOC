# Wisteria Visual Style Matching

> **Upload a photo of a style you love. Get the products that match it — in seconds.**

No keywords. No filters. No scrolling through hundreds of pages. A customer uploads a room photo, an Instagram screenshot, or a magazine tearout — and Wisteria's catalog responds with the **Top 8 products that match that aesthetic**, each with a similarity score and a plain-language note explaining *why* it matches.

---

## The Problem We're Solving

Shoppers know what they like when they *see* it — but they often can't describe it in search terms. "Warm, curved, mid-century-ish, like that café I saw on Instagram" is not a keyword. This project bridges that gap: **inspiration in, products out.**

## How It Works (in plain words)

```
  Customer's photo
        │
        ▼
  ① The system "reads" the image
     What's the material? The finish? The era? The mood?
        │
        ▼
  ② It converts the image into a visual fingerprint
     A mathematical signature of the photo's aesthetic
        │
        ▼
  ③ It compares that fingerprint against every product
     in the Wisteria catalog
        │
        ▼
  ④ The best 8 matches come back — scored 0–100,
     each with a short "why this matches" explanation
```

Behind the scenes, two systems work in parallel: one extracts **style DNA** (material, finish, silhouette, era, palette, mood) and another generates the **visual fingerprint**. A vector database finds the closest catalog matches, smart re-ranking blends visual similarity with style-attribute overlap, and a deterministic template engine writes the human-friendly match explanations from the shared attributes.

> **Two interchangeable backends.** Style extraction runs on either a **self-hosted, fine-tuned Qwen2.5-VL-7B** (default) or **Anthropic Claude Vision** — selected at runtime, no code change. See [Choosing the vision backend](#choosing-the-vision-backend-v21--v30).

**Typical response time:** ~2 seconds on production-grade hardware. Repeat uploads of the same image return in under 100 ms thanks to caching.

---

## What the Customer Sees

| | |
|---|---|
| 🖼️ **Upload** | Drag and drop any inspiration image — room photo, screenshot, or scan |
| 🧬 **Style DNA** | The detected attributes are shown back ("rattan · mid-century · warm-neutral · cosy") so the customer can see the system *understood* the image |
| 🛋️ **Top 8 matches** | Product cards with image, name, and a 0–100 similarity score |
| 💬 **Why it matches** | One or two sentences per product, e.g. *"The woven rattan and low curved profile echo the relaxed mid-century feel of your inspiration room."* |

---

## The APIs

All endpoints live under `/api/v1/`. The contract is production-ready — it will not change when the system moves from POC to cloud infrastructure.

### 1. Style Query — the core experience

```
POST /api/v1/style/query
```

Submit an inspiration image, receive matched products.

**Request** (`multipart/form-data`)

| Field | Type | Required | Description |
|---|---|---|---|
| `image` | file | ✅ | JPEG / PNG / WebP, max 10 MB |
| `top_k` | integer | — | How many results to return (default 8, max 20) |
| `filter_era` | string | — | Optionally restrict matches to one era (e.g. `mid-century`) |
| `filter_material` | string | — | Optionally restrict matches to one material (e.g. `rattan`) |

**Response** (200 OK)

```json
{
  "query_id": "0c9f-…",
  "cache_hit": false,
  "latency_ms": 1843,
  "low_confidence": false,
  "query_attributes": {
    "finish": "natural",
    "material": "rattan",
    "silhouette": "curved",
    "era": "mid-century",
    "palette": "warm-neutral",
    "mood": "cosy"
  },
  "results": [
    {
      "product_id": "123",
      "name": "Rattan Lounge Chair",
      "image_url": "/static/catalog/rattan-lounge.jpg",
      "similarity_score": 77,
      "matched_attributes": { "material": "rattan", "era": "mid-century" },
      "why_matches": "The woven rattan and low curved profile echo the relaxed mid-century feel of your inspiration room."
    }
  ]
}
```

> **Good to know:** if the catalog has no strong match for an unusual image, the response sets `low_confidence: true` so the UI can say *"closest matches we found"* instead of over-promising.

### 2. Health Check

```
GET /api/v1/style/health
```

One call tells you whether every dependency is alive — the vision model server, the database, the cache, and the AI API.

```json
{ "clip": "ok", "postgres": "ok", "redis": "ok", "claude": "ok" }
```

### 3. Catalog Indexing (admin)

```
POST /api/v1/admin/index
```

Triggers (re-)indexing of the product catalog — every product image is analyzed once and stored as a searchable fingerprint. Returns `202 Accepted`; the job runs in the background. Returns `409 Conflict` if a run is already in progress.

### 4. Indexing Status (admin)

```
GET /api/v1/admin/index-status
```

Poll the progress of an indexing run.

```json
{ "status": "RUNNING", "total": 50, "indexed": 34, "skipped": 1, "elapsedMs": 182000 }
```

---

## Graceful by Design

The system degrades, it doesn't break:

- **AI explanation unavailable?** → A clean template note is shown instead. The customer still gets results.
- **Style extraction unavailable?** → Pure visual matching still runs. Results are returned without attribute tags.
- **Cache unavailable?** → Queries simply run a little slower. Nothing fails.

The only hard dependency is the visual fingerprint engine itself — without it, the request returns a clear, retryable error.

## Quality Bar

Before sign-off, the POC is validated against a **golden set of 15 real-world inspiration images** (product shots, room scenes, Instagram screenshots, magazine scans). Target: **at least 60% of returned products judged relevant by a human reviewer** (precision@8), with measured response times logged per query.

---

## Technology at a Glance

| Layer | Technology |
|---|---|
| Frontend | React 18 + Vite + TypeScript |
| Orchestration | Spring Boot 3.2 · Java 21 virtual threads |
| Style extraction | Self-hosted, fine-tuned Qwen2.5-VL-7B *(default)* — or Anthropic Claude Vision |
| Match explanations | Deterministic template engine *(default)* — or Claude Haiku |
| Visual fingerprints | OpenCLIP ViT-L/14 (768-dim embeddings) |
| Similarity search | PostgreSQL + pgvector (HNSW index) |
| Response cache | Redis (perceptual-hash keys) |
| Local runtime | Docker Compose |

## Project Structure

```
wisteria-style-matching/
├── services/           → runnable services
│   ├── api/            → Spring Boot orchestration & REST APIs
│   ├── clip/           → Python visual-fingerprint service (CLIP)
│   ├── vlm/            → Python self-hosted style-extraction service (Qwen2.5-VL, v3.0)
│   └── ui/             → React customer-facing frontend
├── ml/training/        → Distillation & LoRA fine-tune scripts for the VLM (v3.0)
├── data/catalog/       → Product manifest + images (drop-in, gitignored images)
├── docs/               → HLD / LLD design docs
├── docker-compose.yml  → One-command local infrastructure
└── README.md           → You are here
```

## Running It Locally

Full step-by-step setup lives in the design documentation. The short version:

```bash
cp .env.example .env          # Anthropic key only needed for the optional Claude providers
docker compose up -d          # database, cache, CLIP + VLM servers
(cd services/api && ../../mvnw spring-boot:run -Dspring-boot.run.profiles=index)   # index catalog (one-time)
./run-app.sh                  # start the API (loads .env)
cd services/ui && npm install && npm run dev   # open http://localhost:3000
```

First start downloads the CLIP model (~1.7 GB) — allow a few minutes.

### Choosing the vision backend (v2.1 ↔ v3.0)

Two style-extraction providers coexist behind one config switch; the rest of the pipeline (CLIP, pgvector, re-ranking) is identical either way. **Defaults are the v3.0 self-hosted stack** (`vlm` + `template`); switch the alternatives to fall back to the v2.1 hosted path.

| Property (env var) | Default | Alternative |
|---|---|---|
| `wisteria.vision.provider` (`VISION_PROVIDER`) | `vlm` — self-hosted Qwen2.5-VL on `:5002` | `claude` — Anthropic Claude Vision |
| `wisteria.explain.provider` (`EXPLAIN_PROVIDER`) | `template` — deterministic engine | `claude` — Claude Haiku |
| `wisteria.vlm.url` (`VLM_SERVER_URL`) | `http://localhost:5002` | — |

The default vision path is self-hosted — start the VLM server, then run normally (no API key needed at runtime):

```bash
docker compose up -d vlm                  # FastAPI + Qwen2.5-VL on :5002 (GPU recommended)
# Apple Silicon: run on the host instead — Docker has no MPS access:
#   cd services/vlm && pip install -r requirements.txt && uvicorn main:app --port 5002
./run-app.sh                              # vlm is the default provider

# To use the hosted Claude vision path instead:
(cd services/api && VISION_PROVIDER=claude ANTHROPIC_API_KEY=sk-ant-... ../../mvnw spring-boot:run)
```

The VLM downloads the ~16 GB Qwen2.5-VL-7B checkpoint on first start. Drop a fine-tuned LoRA checkpoint in `services/vlm/adapter/` to use it instead of the base model — see `ml/training/`. **Heads-up:** with `vlm` as the default, indexing skips every product if the VLM server isn't reachable — start it first, or run indexing with `VISION_PROVIDER=claude`.

---

## Roadmap Beyond the POC

- **Cloud migration** — the local services map 1:1 to AWS (SageMaker, RDS, ElastiCache, S3); the API contract and business logic do not change.
- **"Shop this room"** — detect multiple focal objects in a single room photo and match each one individually.
- **Feedback loop** — use customer clicks on results to tune the matching weights over time.

---

*Wisteria Visual Style Matching · v3.0 (self-hosted vision) coexisting with v2.1 · Local POC*