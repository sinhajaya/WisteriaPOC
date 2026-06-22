# ───────────────────────────────────────────────────────────
# Wisteria VLM style-extraction server (LLD v3.0, Day 2)
#
# FastAPI + fine-tuned Qwen2.5-VL-7B. Self-hosted replacement for the
# Claude Vision call. Loads the LoRA adapter from adapter/ if present,
# otherwise falls back to the base instruct model for initial validation.
#
#   POST /extract  {image_b64} -> {category, finish, material, silhouette,
#                                  era, palette, mood, model_ver}
#   GET  /health               -> {status, device, model_ver}
#
# The enum vocabularies below MUST stay in lock-step with the Claude tool
# schema (ClaudeStyleService.buildTool) so attribute-overlap scoring is
# comparable across providers.
# ───────────────────────────────────────────────────────────
import base64
import io
import json
import os
import re

import torch
from fastapi import FastAPI, HTTPException
from PIL import Image, ImageOps
from pydantic import BaseModel
from transformers import AutoModelForImageTextToText, AutoProcessor

# POC default is the 2B variant — minimal RAM (~4.4 GB bf16), fits a typical
# ~8 GB Docker memory cap. AutoModelForImageTextToText loads any compatible VLM,
# so this is a pure config knob: set VLM_BASE_MODEL=Qwen/Qwen2.5-VL-7B-Instruct
# (or the fine-tuned checkpoint) on a GPU box.
BASE_MODEL = os.environ.get("VLM_BASE_MODEL", "Qwen/Qwen2-VL-2B-Instruct")
ADAPTER_DIR = os.environ.get("VLM_ADAPTER_DIR", "adapter/qwen2.5-vl-7b-wisteria-v1")

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

SYSTEM = (
    "You are an interior-design vision system. Identify the main furniture/object "
    "and its style attributes. First pick `category` — the KIND of object (a sideboard "
    "or cabinet is `storage`, a chair or sofa is `seating`). Choose exactly one value "
    "per field, only from the allowed vocabularies. Respond with a single JSON object "
    "and nothing else."
)


def _build_prompt() -> str:
    lines = [SYSTEM, "", "Allowed values:"]
    for field, values in ENUMS.items():
        lines.append(f"- {field}: {', '.join(values)}")
    lines.append("")
    lines.append('Return JSON: {"category": "...", "finish": "...", "material": "...", '
                 '"silhouette": "...", "era": "...", "palette": "...", "mood": "..."}')
    return "\n".join(lines)


PROMPT = _build_prompt()

app = FastAPI(title="Wisteria VLM Server")

if torch.cuda.is_available():
    device, dtype = "cuda", torch.float16
elif torch.backends.mps.is_available():
    device, dtype = "mps", torch.float16
else:
    device, dtype = "cpu", torch.bfloat16   # bf16 halves RAM vs fp32 for the CPU POC

_load = {"torch_dtype": dtype, "low_cpu_mem_usage": True}

if os.path.isdir(ADAPTER_DIR):
    from peft import PeftModel

    MODEL_VER = os.path.basename(ADAPTER_DIR.rstrip("/"))
    base = AutoModelForImageTextToText.from_pretrained(BASE_MODEL, **_load)
    model = PeftModel.from_pretrained(base, ADAPTER_DIR)
else:
    MODEL_VER = BASE_MODEL.split("/")[-1].lower() + "-base"
    model = AutoModelForImageTextToText.from_pretrained(BASE_MODEL, **_load)

# Cap image resolution → fewer vision tokens → far faster CPU/MPS inference.
MAX_PIXELS = int(os.environ.get("VLM_MAX_PIXELS", str(256 * 28 * 28)))
processor = AutoProcessor.from_pretrained(BASE_MODEL, max_pixels=MAX_PIXELS)
model = model.to(device).eval()


class ExtractRequest(BaseModel):
    image_b64: str


def _coerce(raw: dict) -> dict:
    out = {}
    for field, allowed in ENUMS.items():
        value = str(raw.get(field, "")).strip().lower()
        out[field] = value if value in allowed else None
    return out


def _parse_json(text: str) -> dict:
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise HTTPException(status_code=502, detail="vlm_no_json")
    try:
        return json.loads(match.group(0))
    except json.JSONDecodeError:
        raise HTTPException(status_code=502, detail="vlm_bad_json")


@app.post("/extract")
def extract(req: ExtractRequest):
    try:
        raw = base64.b64decode(req.image_b64)
        img = Image.open(io.BytesIO(raw))
        img = ImageOps.exif_transpose(img).convert("RGB")
    except Exception:
        raise HTTPException(status_code=400, detail="invalid_image")

    messages = [{
        "role": "user",
        "content": [{"type": "image", "image": img}, {"type": "text", "text": PROMPT}],
    }]
    chat = processor.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = processor(text=[chat], images=[img], return_tensors="pt").to(device)

    with torch.no_grad():
        generated = model.generate(**inputs, max_new_tokens=256, do_sample=False)
    trimmed = generated[:, inputs.input_ids.shape[1]:]
    text = processor.batch_decode(trimmed, skip_special_tokens=True)[0]

    attrs = _coerce(_parse_json(text))
    attrs["model_ver"] = MODEL_VER
    return attrs


@app.get("/health")
def health():
    return {"status": "ok", "device": device, "model_ver": MODEL_VER}