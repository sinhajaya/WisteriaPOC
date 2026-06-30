# ───────────────────────────────────────────────────────────
# Wisteria CLIP embedding server (LLD v2.1, Day 1)
#
# FastAPI + open_clip ViT-L/14. ALL CLIP preprocessing (resize,
# centre-crop, mean/std normalisation) happens here via open_clip's
# own transform — the Java layer must NOT attempt CLIP normalisation.
#
#   POST /embed     {image_b64} -> {embedding: float[768], model_ver}
#   POST /classify  {image_b64} -> {furniture_prob, top_label, is_furniture_label}
#   GET  /health               -> {status, device, model_ver}
# ───────────────────────────────────────────────────────────
import base64
import io

import open_clip
import torch
from fastapi import FastAPI, HTTPException
from PIL import Image, ImageOps
from pydantic import BaseModel

app = FastAPI(title="Wisteria CLIP Server")

MODEL_VER = "ViT-L-14/openai"

model, _, preprocess = open_clip.create_model_and_transforms(
    "ViT-L-14", pretrained="openai"
)
tokenizer = open_clip.get_tokenizer("ViT-L-14")
device = "mps" if torch.backends.mps.is_available() else "cpu"
model = model.to(device).eval()

# ── Zero-shot furniture gate (open-set rejection) ───────────
# Image→text CLIP separates "is this furniture?" far better than image→image
# cosine: an auto-rickshaw scores high on the vehicle prompts even when its
# incidental image similarity to a chair is ~0.6. We softmax the image over ALL
# prompts and sum the probability mass landing on the furniture group.
FURNITURE_PROMPTS = [
    "a photo of furniture",
    "a photo of a chair",
    "a photo of a sofa",
    "a photo of a table",
    "a photo of a bed",
    "a photo of a lamp",
    "a photo of a cabinet",
    "a photo of a rug",
    "a piece of home decor",
]
NON_FURNITURE_PROMPTS = [
    "a photo of a vehicle",
    "a photo of an auto rickshaw",
    "a photo of a car",
    "a photo of a person",
    "a photo of an animal",
    "a photo of food",
    "a photo of a building",
    "an outdoor street scene",
    "a photo of a landscape",
    # Small objects / hardware / fixtures / close-ups. The gate previously had no
    # negative for these, so a door knob, faucet or switch had nowhere for its
    # softmax mass to go except the furniture group, and passed the gate.
    "a photo of a door knob",
    "a photo of a door handle",
    "a close-up photo of door hardware",
    "a photo of a faucet",
    "a photo of a light switch or power outlet",
    "a photo of a tool",
    "a photo of jewelry",
    "a photo of an electronic device",
]
_ALL_PROMPTS = FURNITURE_PROMPTS + NON_FURNITURE_PROMPTS
_N_FURNITURE = len(FURNITURE_PROMPTS)

with torch.no_grad():
    _text_features = model.encode_text(tokenizer(_ALL_PROMPTS).to(device))
    _text_features = _text_features / _text_features.norm(dim=-1, keepdim=True)
_logit_scale = model.logit_scale.exp()


def _decode_image(image_b64: str) -> Image.Image:
    try:
        raw = base64.b64decode(image_b64)
        img = Image.open(io.BytesIO(raw))
        # Fix phone-photo rotation before CLIP sees it.
        return ImageOps.exif_transpose(img).convert("RGB")
    except Exception:
        raise HTTPException(status_code=400, detail="invalid_image")


class EmbedRequest(BaseModel):
    image_b64: str


@app.post("/embed")
def embed(req: EmbedRequest):
    img = _decode_image(req.image_b64)
    tensor = preprocess(img).unsqueeze(0).to(device)
    with torch.no_grad():
        vec = model.encode_image(tensor)
        vec = vec / vec.norm(dim=-1, keepdim=True)  # L2-normalise
    return {
        "embedding": vec.squeeze().cpu().tolist(),
        "model_ver": MODEL_VER,
    }


@app.post("/classify")
def classify(req: EmbedRequest):
    """Zero-shot domain gate: how much does this image read as furniture?

    Returns furniture_prob ∈ [0,1] (softmax mass on the furniture prompt group)
    and the single best-matching prompt label. The caller applies its own
    threshold — the server stays policy-free.
    """
    img = _decode_image(req.image_b64)
    tensor = preprocess(img).unsqueeze(0).to(device)
    with torch.no_grad():
        vec = model.encode_image(tensor)
        vec = vec / vec.norm(dim=-1, keepdim=True)
        logits = (_logit_scale * vec @ _text_features.T).squeeze(0)
        probs = logits.softmax(dim=-1)
    furniture_prob = float(probs[:_N_FURNITURE].sum().item())
    top_idx = int(probs.argmax().item())
    return {
        "furniture_prob": furniture_prob,
        "top_label": _ALL_PROMPTS[top_idx],
        "top_is_furniture": top_idx < _N_FURNITURE,
        "model_ver": MODEL_VER,
    }


@app.get("/health")
def health():
    return {"status": "ok", "device": device, "model_ver": MODEL_VER}