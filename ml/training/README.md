# Training — distillation, fine-tune & eval (v3.0)

Produces the fine-tuned style-extraction adapter the VLM server loads
(`VLM_ADAPTER_DIR`). It distills Claude's labels into a self-hosted
**Qwen2-VL-2B** LoRA adapter, then gates it on held-out accuracy.

## Scripts
- `taxonomy.py` — closed-vocab enums + prompt. Keep identical to `services/vlm/main.py` and the Java tool schema.
- `fetch_catalog.py` — build a category-balanced catalog from wisteria.com → `../../data/catalog/{products.json,images/}`.
- `label_with_claude.py` — Claude (forced tool use) labels catalog images → `data/labeled.jsonl`. **Incremental**: re-runs skip images already labeled under the current taxonomy (`criteria_version`); atomic write never truncates the dataset.
- `run_pipeline.py` — **one-command GPU pipeline**: label → train → eval(held-out) → serve.

## Local — build the catalog and the labels
```bash
cd ml/training
pip install -r requirements.txt
python fetch_catalog.py --per-category 12          # → ../../data/catalog/{products.json,images/}
export ANTHROPIC_API_KEY=sk-ant-...
python label_with_claude.py --manifest ../../data/catalog/products.json \
    --image-dir ../../data/catalog/images --out data/labeled.jsonl
```

### Labeling cost (record after each run)
`label_with_claude.py` runs `claude-sonnet-4-6` in standard mode (no extended
thinking, no `temperature`/`top_p`/`top_k`) via the **Message Batches API**
(50% off list price) and prints token spend on exit:

| Date | Model ID | Images | Input tokens | Output tokens | Cost (batch) |
|---|---|---|---|---|---|
| 2026-06-16 | claude-sonnet-4-6 | 96 | 181,123 | 13,646 | ~$0.37 |
| 2026-06-18 | claude-sonnet-4-6 | 366 | 687,243 | 53,206 | ~$1.43 |

## GPU — one-command pipeline (Colab T4)
Fine-tuning needs a GPU. Build the upload bundle, then run everything on Colab:

```bash
# build wisteria_train.tar.gz = labels + the labeled images
rm -rf wisteria_train && mkdir -p wisteria_train/images
cp data/labeled.jsonl wisteria_train/
python3 -c "import json,os,shutil; [shutil.copy('../../data/catalog/images/'+os.path.basename(json.loads(l)['image']),'wisteria_train/images/') for l in open('data/labeled.jsonl') if l.strip()]"
tar czf wisteria_train.tar.gz wisteria_train && rm -rf wisteria_train
```

On Colab (Runtime → T4 GPU), upload `run_pipeline.py` + `wisteria_train.tar.gz`, then:
```bash
!python run_pipeline.py --epochs 3 --serve
```

Stages — mix and match with `--skip-label` / `--skip-train` / `--skip-eval` / `--serve`:
- **label** — incremental Claude labeling via the **Message Batches API** (50% cheaper, runs unattended; needs `ANTHROPIC_API_KEY`; skips already-labeled images; checkpoints after each batch). Teacher model = `--label-model` (default `claude-sonnet-4-6`; pass `claude-haiku-4-5` for ~⅓ the cost — weaker on abstract fields). Tune `--label-batch-size` / `--label-poll-secs`.
- **train** — LoRA on the 80% split → adapter at `/content/qwen2-vl-2b-wisteria-v1`.
- **eval** — accuracy on the **held-out 20%** vs the Claude labels; per-attribute scores are printed, the **overall** mean is gated against `--min-accuracy` (default 0.40 at POC scale). Advisory only — a FAIL never blocks `--serve`.
- **serve** — start `/extract` + `/health` and print a public `VLM_SERVER_URL`; paste it into the app's `.env`. Serving reuses the training processor, so train/serve resolution matches.

```bash
!python run_pipeline.py --skip-label --skip-train --skip-eval --serve   # serve a saved adapter
!python run_pipeline.py --skip-label --skip-train                        # eval-only
```

> POC scale: 96 labeled images is a *pipeline* demo, not a tuned model — scale the
> catalog/labels toward thousands (balanced, so rare values aren't singletons) for
> real accuracy. The held-out eval gate (overall ≥ `--min-accuracy`, 0.40 at this
> scale; raise it as the dataset grows) pairs with the end-to-end precision@8 ≥60%
> retrieval check before deploying a checkpoint.