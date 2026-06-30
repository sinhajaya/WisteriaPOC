"""Single source of truth for the style taxonomy used across distillation,
fine-tuning, and evaluation.

These enums MUST stay identical to ClaudeStyleService.buildTool (Java) and
services/vlm/main.py (ENUMS). A drift here silently corrupts attribute-overlap
scoring at query time.
"""

ENUMS = {
    "category": ["seating", "table", "storage", "bed", "lighting", "rug", "decor", "textile", "tabletop", "other"],
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

FIELDS = list(ENUMS.keys())

SYSTEM = (
    "You are an interior-design vision system. Identify the main furniture/object "
    "and its style attributes. First pick `category` — the KIND of object, by what it "
    "physically is (not its style). Use this guidance: a chair, sofa, stool, or bench "
    "is `seating`; a sideboard, cabinet, dresser, shelf, or bookcase is `storage`; a "
    "desk, dining, coffee, or side table is `table`; a bed or headboard is `bed`; a "
    "lamp, pendant, or sconce is `lighting`; a rug or carpet is `rug`; a vase, bowl, "
    "jar, sculpture, mirror, artwork, tray, basket, or other decorative accessory is "
    "`decor`; a pillow, throw, blanket, duvet, quilt, bedding, or table linen is "
    "`textile`; a plate, glass, mug, pitcher, or serveware piece is `tabletop`; use "
    "`other` only when nothing else fits. Choose exactly one value per field, only "
    "from the allowed vocabularies."
)

EXTRACT_TOOL = {
    "name": "extract_style_attributes",
    "description": "Extract interior-design style attributes visible in the image.",
    "input_schema": {
        "type": "object",
        "properties": {f: {"type": "string", "enum": v} for f, v in ENUMS.items()},
        "required": FIELDS,
    },
}


def prompt_text() -> str:
    """The user-turn instruction shared by the VLM at train and eval time."""
    lines = [SYSTEM, "", "Allowed values:"]
    lines += [f"- {f}: {', '.join(v)}" for f, v in ENUMS.items()]
    lines.append("")
    lines.append("Return a single JSON object with one value per field.")
    return "\n".join(lines)


def coerce(raw: dict) -> dict:
    """Keep only in-vocabulary values; everything else becomes None."""
    out = {}
    for field, allowed in ENUMS.items():
        value = str(raw.get(field, "")).strip().lower()
        out[field] = value if value in allowed else None
    return out