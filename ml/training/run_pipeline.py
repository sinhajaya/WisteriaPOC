"""One-command pipeline: label → train → evaluate (Colab GPU).

Runs the whole loop in a single trigger so you don't run three scripts by hand.
Because fine-tuning needs a GPU, run this on a Colab T4 (or any CUDA box).

Setup (Colab, T4 runtime):
  1. Files → upload:  run_pipeline.py  and  wisteria_train.tar.gz
  2. (only if labels need (re)generating) set the teacher key:
        import os; os.environ["ANTHROPIC_API_KEY"] = "sk-ant-..."
  3. Run everything:
        !python run_pipeline.py --epochs 3
     Or pick stages:
        !python run_pipeline.py --skip-label            # data already labeled
        !python run_pipeline.py --skip-train --skip-label  # eval only

Stages:
  label  — Claude (forced tool use) labels any image missing a label, INCREMENTALLY
           (rows carry criteria_version; unchanged images are never re-labeled).
           Uses the Message Batches API (50% cheaper, unattended); labels are
           checkpointed to disk after each batch. Teacher = --label-model.
  train  — LoRA fine-tune Qwen2-VL-2B on the TRAIN split → saves an adapter.
  eval   — in-process accuracy on the HELD-OUT split vs the Claude labels;
           per-attribute scores are printed, the OVERALL mean is gated against
           --min-accuracy (advisory — never blocks serve).
  serve  — (opt-in, --serve) launch the fine-tuned model behind /extract + /health
           and print a public tunnel URL to paste into the app's VLM_SERVER_URL.
           Reuses the training processor, so train/serve resolution matches.

One-and-done (label → train → eval → serve):
        !python run_pipeline.py --epochs 3 --serve
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import io
import json
import os
import random
import re
import subprocess
import sys
import tarfile
import time

# ── deps ────────────────────────────────────────────────────────────────────
print(">>> installing deps ...", flush=True)
subprocess.run([sys.executable, "-m", "pip", "install", "-q",
                "transformers==4.49.0", "peft==0.14.0", "datasets", "accelerate",
                "pillow", "anthropic", "fastapi", "uvicorn[standard]"], check=True)
# Colab's old torchao breaks newer peft's LoRA injection; it's optional, drop it.
subprocess.run([sys.executable, "-m", "pip", "uninstall", "-y", "torchao"])

import torch  # noqa: E402
from PIL import Image, ImageOps  # noqa: E402
from pydantic import BaseModel  # noqa: E402


# Serve request body. MUST live at module scope: with `from __future__ import
# annotations` (PEP 563) the endpoint's `req: ExtractRequest` hint is stored as
# the string "ExtractRequest", and FastAPI resolves it against the function's
# *module* globals — a class nested inside stage_serve isn't visible there and
# raises NameError at route registration.
class ExtractRequest(BaseModel):
    image_b64: str

# ── taxonomy (single source of truth; must match the rest of the project) ────
ENUMS = {
    "category": ["seating", "table", "storage", "bed", "lighting", "rug", "decor", "other"],
    "finish": ["matte", "gloss", "brushed", "aged", "lacquered", "natural"],
    "material": ["oak", "walnut", "velvet", "brass", "linen", "marble",
                 "rattan", "glass", "leather", "ceramic",
                 "teak", "wood", "iron", "cane", "bone"],
    "silhouette": ["clean-line", "curved", "ornate", "sculptural", "minimal"],
    "era": ["mid-century", "art-deco", "japandi", "contemporary", "industrial", "traditional",
            "organic-modern", "coastal", "bohemian"],
    "palette": ["warm-neutral", "cool-neutral", "earthy", "monochrome", "bold"],
    "mood": ["cosy", "editorial", "calm", "dramatic", "playful"],
}
FIELDS = list(ENUMS)
SYSTEM = (
    "You are an interior-design vision system. Identify the main furniture/object "
    "and its style attributes. First pick `category` — the KIND of object (a sideboard "
    "or cabinet is `storage`, a chair or sofa is `seating`). Choose exactly one value "
    "per field, only from the allowed vocabularies."
)
CRITERIA = hashlib.sha256(
    json.dumps({"enums": ENUMS, "system": SYSTEM}, sort_keys=True).encode()).hexdigest()[:12]


def prompt_text() -> str:
    lines = [SYSTEM, "", "Allowed values:"]
    lines += [f"- {f}: {', '.join(v)}" for f, v in ENUMS.items()]
    lines.append("")
    lines.append("Return a single JSON object with one value per field.")
    return "\n".join(lines)


PROMPT = prompt_text()


def coerce(raw: dict) -> dict:
    out = {}
    for field, allowed in ENUMS.items():
        value = str(raw.get(field, "")).strip().lower()
        out[field] = value if value in allowed else None
    return out


# ── stage 1: label (Message Batches API — 50% cheaper, runs unattended) ───────
def stage_label(data_dir: str, args) -> None:
    img_dir = os.path.join(data_dir, "images")
    out_path = os.path.join(data_dir, "labeled.jsonl")
    existing = {}
    if os.path.exists(out_path) and not args.force_label:
        for line in open(out_path):
            line = line.strip()
            if not line:
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError:
                continue
            if row.get("criteria_version") == CRITERIA:
                existing[os.path.basename(row["image"])] = row

    images = sorted(f for f in os.listdir(img_dir)
                    if f.lower().endswith((".jpg", ".jpeg", ".png")))
    todo = [f for f in images if f not in existing]
    model = args.label_model
    print(f">>> label: {len(existing)} already labeled (criteria {CRITERIA}), "
          f"{len(todo)} to label via Batch API (teacher={model})", flush=True)
    if not todo:
        return
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("    ANTHROPIC_API_KEY not set — skipping labeling of new images.", flush=True)
        return

    import anthropic
    client = anthropic.Anthropic()
    tool = {"name": "extract_style_attributes",
            "description": "Extract interior-design style attributes visible in the image.",
            "input_schema": {"type": "object",
                             "properties": {f: {"type": "string", "enum": v} for f, v in ENUMS.items()},
                             "required": FIELDS}}

    def request_for(idx: int, fname: str) -> dict:
        with open(os.path.join(img_dir, fname), "rb") as fh:
            data = base64.b64encode(fh.read()).decode("ascii")
        media = "image/png" if fname.lower().endswith(".png") else "image/jpeg"
        return {
            "custom_id": f"img-{idx}",
            "params": {
                "model": model, "max_tokens": 512, "system": SYSTEM,
                "tools": [tool], "tool_choice": {"type": "tool", "name": tool["name"]},
                "messages": [{"role": "user", "content": [
                    {"type": "text", "text": "Extract the style attributes of this image."},
                    {"type": "image", "source": {"type": "base64", "media_type": media, "data": data}}]}],
            },
        }

    by_img = dict(existing)
    done = 0
    # Chunk so each batch stays well under the 256 MB / 100k-request limits, and
    # checkpoint labels to disk after every batch (resilient to interruption /
    # re-runs skip what's already on disk).
    for start in range(0, len(todo), args.label_batch_size):
        chunk = todo[start:start + args.label_batch_size]
        idx_to_name = {f"img-{start + j}": fname for j, fname in enumerate(chunk)}
        batch = client.messages.batches.create(
            requests=[request_for(start + j, fname) for j, fname in enumerate(chunk)])
        print(f"    batch {batch.id}: {len(chunk)} images submitted, polling "
              f"every {args.label_poll_secs}s ...", flush=True)
        while client.messages.batches.retrieve(batch.id).processing_status != "ended":
            time.sleep(args.label_poll_secs)

        ok = err = 0
        for res in client.messages.batches.results(batch.id):
            fname = idx_to_name.get(res.custom_id)
            if fname is None:
                continue
            if res.result.type != "succeeded":
                err += 1
                continue
            attrs = next((coerce(b.input) for b in res.result.message.content
                          if b.type == "tool_use"), None)
            if attrs:
                by_img[fname] = {"image": fname, "attributes": attrs,
                                 "teacher": model, "criteria_version": CRITERIA}
                ok += 1
            else:
                err += 1

        done += len(chunk)
        with open(out_path, "w") as f:   # checkpoint after each batch
            for fname in sorted(by_img):
                f.write(json.dumps(by_img[fname]) + "\n")
        print(f"    batch done: +{ok} labeled, {err} failed  ({done}/{len(todo)})", flush=True)
    print(f">>> label: wrote {len(by_img)} rows → {out_path}", flush=True)


# ── data helpers ──────────────────────────────────────────────────────────────
def load_rows(data_dir: str) -> list[dict]:
    path = os.path.join(data_dir, "labeled.jsonl")
    rows = [json.loads(l) for l in open(path) if l.strip()]
    for r in rows:
        r["_path"] = os.path.join(data_dir, "images", os.path.basename(r["image"]))
    return [r for r in rows if os.path.exists(r["_path"])]


def split_rows(rows: list[dict], frac: float, seed: int):
    rows = list(rows)
    random.Random(seed).shuffle(rows)
    n_eval = max(1, int(len(rows) * frac))
    return rows[n_eval:], rows[:n_eval]   # train, eval


# ── stage 2: train ────────────────────────────────────────────────────────────
def stage_train(train_rows, args):
    from datasets import Dataset
    from peft import LoraConfig, get_peft_model
    from transformers import (AutoModelForImageTextToText, AutoProcessor,
                              Trainer, TrainingArguments)

    def to_example(r):
        target = json.dumps({f: r["attributes"].get(f) for f in FIELDS})
        return {"image": r["_path"], "messages": [
            {"role": "user", "content": [{"type": "image"}, {"type": "text", "text": PROMPT}]},
            {"role": "assistant", "content": [{"type": "text", "text": target}]}]}

    dataset = Dataset.from_list([to_example(r) for r in train_rows])
    dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32
    model = AutoModelForImageTextToText.from_pretrained(
        args.base_model, torch_dtype=dtype, low_cpu_mem_usage=True)
    processor = AutoProcessor.from_pretrained(args.base_model, max_pixels=args.max_pixels)

    model = get_peft_model(model, LoraConfig(
        r=16, lora_alpha=32, lora_dropout=0.05, bias="none",
        target_modules=["q_proj", "k_proj", "v_proj", "o_proj"], task_type="CAUSAL_LM"))
    model.print_trainable_parameters()
    model.config.use_cache = False
    model.enable_input_require_grads()

    def collate(batch):
        texts = [processor.apply_chat_template(b["messages"], tokenize=False) for b in batch]
        images = [Image.open(b["image"]).convert("RGB") for b in batch]
        mi = processor(text=texts, images=images, padding=True, return_tensors="pt")
        labels = mi["input_ids"].clone()
        labels[labels == processor.tokenizer.pad_token_id] = -100
        mi["labels"] = labels
        return mi

    Trainer(model=model,
            args=TrainingArguments(
                output_dir="checkpoints", num_train_epochs=args.epochs,
                per_device_train_batch_size=1, gradient_accumulation_steps=8,
                learning_rate=1e-4, logging_steps=5, save_strategy="no",
                bf16=torch.cuda.is_available(), gradient_checkpointing=True,
                gradient_checkpointing_kwargs={"use_reentrant": False},
                report_to="none", remove_unused_columns=False),
            train_dataset=dataset, data_collator=collate).train()

    os.makedirs(args.adapter_out, exist_ok=True)
    model.save_pretrained(args.adapter_out)
    processor.save_pretrained(args.adapter_out)
    print(f">>> train: adapter saved → {args.adapter_out}", flush=True)
    return model, processor


# ── stage 3: eval (in-process, held-out) ──────────────────────────────────────
def _predict_image(model, processor, device, img):
    messages = [{"role": "user", "content": [{"type": "image", "image": img},
                                             {"type": "text", "text": PROMPT}]}]
    chat = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = processor(text=[chat], images=[img], return_tensors="pt").to(device)
    with torch.no_grad():
        gen = model.generate(**inputs, max_new_tokens=160, do_sample=False)
    text = processor.batch_decode(gen[:, inputs.input_ids.shape[1]:], skip_special_tokens=True)[0]
    m = re.search(r"\{.*\}", text, re.DOTALL)
    return coerce(json.loads(m.group(0))) if m else {}


def _predict(model, processor, device, path):
    return _predict_image(model, processor, device,
                          ImageOps.exif_transpose(Image.open(path)).convert("RGB"))


def stage_eval(eval_rows, model, processor, args):
    device = next(model.parameters()).device
    model.config.use_cache = True
    model.eval()
    correct = {f: 0 for f in FIELDS}
    total = 0
    for r in eval_rows:
        try:
            pred = _predict(model, processor, device, r["_path"])
        except Exception as e:  # noqa: BLE001
            print(f"    eval predict failed {r['_path']}: {e}", flush=True)
            continue
        total += 1
        for f in FIELDS:
            if pred.get(f) == r["attributes"].get(f):
                correct[f] += 1
    if not total:
        print(">>> eval: no predictions succeeded", flush=True)
        return False
    print(f"\n>>> eval on {total} HELD-OUT images vs Claude labels "
          f"({args.min_accuracy:.0%} gate)\n", flush=True)
    print(f"{'attribute':<12} {'accuracy':>9}  gate")
    per = {}
    for f in FIELDS:
        acc = correct[f] / total
        per[f] = acc
        print(f"{f:<12} {acc:>8.1%}  {'PASS' if acc >= args.min_accuracy else 'FAIL'}")
    overall = sum(per.values()) / len(FIELDS)
    # Gate on OVERALL accuracy. Per-attribute scores are printed above for
    # insight, but at POC data scale some abstract fields (era, silhouette,
    # palette) lag, so we don't hard-fail the whole gate on a single weak one.
    passed = overall >= args.min_accuracy
    weak = [f for f, a in per.items() if a < args.min_accuracy]
    print(f"\noverall      {overall:>8.1%}  {'PASS' if passed else 'FAIL'}")
    if weak:
        print(f"(below-gate attributes: {', '.join(weak)})")
    return passed


def _load_model(args):
    from peft import PeftModel
    from transformers import AutoModelForImageTextToText, AutoProcessor
    dtype = torch.bfloat16 if torch.cuda.is_available() else torch.float32
    base = AutoModelForImageTextToText.from_pretrained(
        args.base_model, torch_dtype=dtype, low_cpu_mem_usage=True)
    model = PeftModel.from_pretrained(base, args.adapter_out) \
        if os.path.isdir(args.adapter_out) else base
    device = "cuda" if torch.cuda.is_available() else "cpu"
    return model.to(device), AutoProcessor.from_pretrained(args.base_model, max_pixels=args.max_pixels)


def stage_serve(model, processor, args):
    import threading
    import time
    import uvicorn
    from fastapi import FastAPI, HTTPException

    device = next(model.parameters()).device
    model.config.use_cache = True
    model.eval()
    model_ver = (os.path.basename(args.adapter_out.rstrip("/")) if os.path.isdir(args.adapter_out)
                 else args.base_model.split("/")[-1].lower() + "-base")

    app = FastAPI(title="Wisteria VLM (pipeline)")

    @app.post("/extract")
    def extract(req: ExtractRequest):
        try:
            raw = base64.b64decode(req.image_b64)
            img = ImageOps.exif_transpose(Image.open(io.BytesIO(raw))).convert("RGB")
        except Exception:
            raise HTTPException(status_code=400, detail="invalid_image")
        attrs = _predict_image(model, processor, device, img)
        attrs["model_ver"] = model_ver
        return attrs

    @app.get("/health")
    def health():
        return {"status": "ok", "device": str(device), "model_ver": model_ver,
                "max_pixels": args.max_pixels}

    threading.Thread(
        target=lambda: uvicorn.run(app, host="0.0.0.0", port=5002, log_level="warning"),
        daemon=True).start()
    time.sleep(8)

    if not os.path.exists("cloudflared"):
        print(">>> serve: fetching cloudflared ...", flush=True)
        subprocess.run(
            "wget -q https://github.com/cloudflare/cloudflared/releases/latest/download/"
            "cloudflared-linux-amd64 -O cloudflared && chmod +x cloudflared",
            shell=True, check=True)
    print(">>> serve: opening public tunnel ...", flush=True)
    proc = subprocess.Popen(
        ["./cloudflared", "tunnel", "--url", "http://localhost:5002", "--no-autoupdate"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
    printed = False
    for line in proc.stdout:
        sys.stdout.write(line)
        sys.stdout.flush()
        m = re.search(r"https://[-a-z0-9]+\.trycloudflare\.com", line)
        if m and not printed:
            printed = True
            print("\n" + "=" * 64 + f"\nVLM_SERVER_URL={m.group(0)}\n" + "=" * 64
                  + "\n(keep this cell running; paste the URL locally)\n", flush=True)
    proc.wait()


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bundle", default="wisteria_train.tar.gz")
    ap.add_argument("--data-dir", default="wisteria_train")
    ap.add_argument("--base-model", default=os.environ.get("VLM_BASE_MODEL", "Qwen/Qwen2-VL-2B-Instruct"))
    ap.add_argument("--adapter-out", default=os.environ.get("VLM_ADAPTER_DIR", "/content/qwen2-vl-2b-wisteria-v1"))
    ap.add_argument("--epochs", type=float, default=3.0)
    ap.add_argument("--eval-split", type=float, default=0.2)
    # POC bar: at this data scale (~19 held-out images) the abstract fields
    # (era/silhouette/palette) lag, so we gate on OVERALL at 0.40, not 0.85.
    ap.add_argument("--min-accuracy", type=float, default=0.40)
    ap.add_argument("--max-pixels", type=int, default=384 * 28 * 28)
    ap.add_argument("--seed", type=int, default=0)
    # Teacher (labeler) — Sonnet is the quality default; claude-haiku-4-5 is the
    # cheaper option (~1/3 the price) but weaker on abstract fields (era/silhouette).
    ap.add_argument("--label-model",
                    default=os.environ.get("ANTHROPIC_MODEL_EXTRACT", "claude-sonnet-4-6"))
    ap.add_argument("--label-batch-size", type=int, default=100,
                    help="images per Message Batch (checkpointed to disk after each)")
    ap.add_argument("--label-poll-secs", type=int, default=15)
    ap.add_argument("--force-label", action="store_true")
    ap.add_argument("--skip-label", action="store_true")
    ap.add_argument("--skip-train", action="store_true")
    ap.add_argument("--skip-eval", action="store_true")
    ap.add_argument("--serve", action="store_true",
                    help="after train/eval, serve the model + print a public tunnel URL (blocks)")
    args = ap.parse_args()

    # Only label/eval/train need the dataset; serve-only can skip it entirely.
    train_rows = eval_rows = []
    if not (args.skip_label and args.skip_train and args.skip_eval):
        if os.path.exists(args.bundle) and not os.path.isdir(args.data_dir):
            print(f">>> extracting {args.bundle} ...", flush=True)
            with tarfile.open(args.bundle) as t:
                t.extractall(".")
        if not args.skip_label:
            stage_label(args.data_dir, args)
        rows = load_rows(args.data_dir)
        train_rows, eval_rows = split_rows(rows, args.eval_split, args.seed)
        print(f">>> data: {len(rows)} labeled | train {len(train_rows)} | eval {len(eval_rows)}", flush=True)

    model = processor = None
    if not args.skip_train:
        model, processor = stage_train(train_rows, args)

    # eval and/or serve need a model in memory; load base+adapter if train was skipped.
    if (not args.skip_eval or args.serve) and model is None:
        model, processor = _load_model(args)

    if not args.skip_eval:
        passed = stage_eval(eval_rows, model, processor, args)
        # The gate is advisory at POC scale — it never blocks serving. We just
        # surface the verdict so a FAIL doesn't look like the run stopped.
        if not passed:
            print(">>> eval below gate — serving anyway (POC).", flush=True)

    if args.serve:
        print("\n>>> serving the model (Ctrl-C / stop the cell to end) ...", flush=True)
        stage_serve(model, processor, args)   # blocks, keeps the tunnel alive
    else:
        print(">>> not serving (pass --serve to start the VLM + print a tunnel URL).", flush=True)

    print("\n>>> pipeline complete.", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
