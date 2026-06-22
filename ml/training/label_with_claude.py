"""Phase 1 — Distillation labeling with Claude (teacher).

Use Claude Vision (forced tool use) to label catalog images with the closed-vocab
style attributes, producing a JSONL training set for the VLM student.

Incremental by default: each row is stamped with a `criteria_version` (a hash of
the taxonomy + system prompt). A run re-labels only images that are missing or
whose stored `criteria_version` differs from the current one — so unchanged
images are never re-labeled (and never re-billed). Pass --force to relabel all.
The output is written atomically (kept rows are always re-written), so a partial
or rate-limited run can never truncate the dataset.

Usage:
    export ANTHROPIC_API_KEY=sk-ant-...
    python label_with_claude.py \
        --manifest ../../data/catalog/products.json \
        --image-dir ../../data/catalog/images \
        --out data/labeled.jsonl
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import sys
import time

import anthropic

from taxonomy import ENUMS, EXTRACT_TOOL, SYSTEM, coerce

# ── Labeling determinism (HLD v3.0) ─────────────────────────────────────────
# Teacher is claude-sonnet-4-6 in STANDARD mode. Do NOT enable extended thinking
# and do NOT send temperature/top_p/top_k: forced tool use already pins the
# output, and defaults keep labels reproducible. After a run, record the model
# ID and printed token spend in training/README.md for the retraining budget.
# ────────────────────────────────────────────────────────────────────────────
MODEL = os.environ.get("ANTHROPIC_MODEL_EXTRACT", "claude-sonnet-4-6")

# Identifies the labeling criteria; changing the taxonomy or system prompt
# changes this, which invalidates stale rows so they get re-labeled.
CRITERIA = hashlib.sha256(
    json.dumps({"enums": ENUMS, "system": SYSTEM}, sort_keys=True).encode()
).hexdigest()[:12]


def encode(path: str) -> tuple[str, str]:
    with open(path, "rb") as f:
        data = base64.b64encode(f.read()).decode("ascii")
    media = "image/png" if path.lower().endswith(".png") else "image/jpeg"
    return data, media


def build_request(custom_id: str, path: str) -> dict:
    """One Message Batches request — same params as the sync call, plus a
    custom_id we map back to the image path when results come in."""
    data, media = encode(path)
    return {
        "custom_id": custom_id,
        "params": {
            "model": MODEL,
            "max_tokens": 512,
            "system": SYSTEM,
            "tools": [EXTRACT_TOOL],
            "tool_choice": {"type": "tool", "name": EXTRACT_TOOL["name"]},
            "messages": [{
                "role": "user",
                "content": [
                    {"type": "text", "text": "Extract the style attributes of this image."},
                    {"type": "image",
                     "source": {"type": "base64", "media_type": media, "data": data}},
                ],
            }],
        },
    }


def load_manifest(manifest: str, image_dir: str) -> list[dict]:
    with open(manifest) as f:
        products = json.load(f)
    items = []
    for p in products:
        rel = p.get("image_path") or p.get("imagePath")
        if not rel:
            continue
        items.append({"id": p.get("id") or p.get("sku"),
                      "path": os.path.join(image_dir, rel.lstrip("/"))})
    return items


def load_existing(path: str) -> dict:
    """image path → row, for rows still valid under the current criteria."""
    kept = {}
    if not os.path.exists(path):
        return kept
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            img = row.get("image")
            if img and row.get("criteria_version") == CRITERIA and os.path.exists(img):
                kept[img] = row
    return kept


def flush(out_path: str, items: list[dict], by_img: dict) -> int:
    """Atomic merge-write in manifest order — kept rows are always re-written,
    so a partial/interrupted run never truncates the dataset."""
    ordered = [by_img[it["path"]] for it in items if it["path"] in by_img]
    tmp = out_path + ".tmp"
    with open(tmp, "w") as f:
        for row in ordered:
            f.write(json.dumps(row) + "\n")
    os.replace(tmp, out_path)
    return len(ordered)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--manifest", default="../../data/catalog/products.json")
    ap.add_argument("--image-dir", default="../../data/catalog/images")
    ap.add_argument("--out", default="data/labeled.jsonl")
    ap.add_argument("--force", action="store_true", help="relabel everything, ignoring existing rows")
    ap.add_argument("--batch-size", type=int, default=100,
                    help="images per Message Batch (checkpointed to disk after each)")
    ap.add_argument("--poll-secs", type=int, default=15)
    args = ap.parse_args()

    client = anthropic.Anthropic()
    items = load_manifest(args.manifest, args.image_dir)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)

    kept = {} if args.force else load_existing(args.out)
    to_label = [it for it in items
                if it["path"] not in kept and os.path.exists(it["path"])]
    print(f"criteria={CRITERIA}  already labeled={len(kept)}  to label={len(to_label)} "
          f"via Batch API (teacher={MODEL})")

    by_img = dict(kept)
    written, failed = 0, 0
    in_tokens, out_tokens = 0, 0

    # Submit in chunks so each batch stays well under the 256 MB / 100k-request
    # limits, and checkpoint labels to disk after every batch.
    for start in range(0, len(to_label), args.batch_size):
        chunk = to_label[start:start + args.batch_size]
        id_to_path = {f"req-{start + j}": it["path"] for j, it in enumerate(chunk)}
        batch = client.messages.batches.create(
            requests=[build_request(f"req-{start + j}", it["path"])
                      for j, it in enumerate(chunk)])
        print(f"  batch {batch.id}: {len(chunk)} images submitted, polling every "
              f"{args.poll_secs}s ...", flush=True)
        while client.messages.batches.retrieve(batch.id).processing_status != "ended":
            time.sleep(args.poll_secs)

        for res in client.messages.batches.results(batch.id):
            path = id_to_path.get(res.custom_id)
            if path is None:
                continue
            if res.result.type != "succeeded":
                print(f"  skip ({res.result.type}): {path}", file=sys.stderr)
                failed += 1
                continue
            msg = res.result.message
            in_tokens += msg.usage.input_tokens
            out_tokens += msg.usage.output_tokens
            attrs = next((coerce(b.input) for b in msg.content if b.type == "tool_use"), None)
            if attrs is None:
                failed += 1
                continue
            by_img[path] = {"image": path, "attributes": attrs,
                            "teacher": MODEL, "criteria_version": CRITERIA}
            written += 1

        total = flush(args.out, items, by_img)
        print(f"  batch done: +{written} labeled so far, {failed} failed  "
              f"(file now {total})", flush=True)

    total = flush(args.out, items, by_img)
    print(f"new labels {written}, failed {failed}, total in file {total} → {args.out}")
    print(f"model={MODEL}  input_tokens={in_tokens}  output_tokens={out_tokens}  "
          f"total_tokens={in_tokens + out_tokens}  (Batch API — 50% off list price)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())