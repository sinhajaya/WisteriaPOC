"""Build a small, category-balanced POC catalog from the live wisteria.com
Shopify store.

Pulls products via the public products.json endpoint, buckets them into our
category vocabulary, keeps N per category, downloads one image each, and writes
the manifest the indexer reads.

Usage:
    python fetch_catalog.py --per-category 6

Outputs:
    ../../data/catalog/products.json   manifest: [{name, category, image_path}, ...]
    ../../data/catalog/images/<file>   one product image per entry
"""
from __future__ import annotations

import argparse
import json
import os
import time
import urllib.error
import urllib.request

BASE = "https://wisteria.com"
UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0 Safari/537.36")


def _get(url: str, timeout: int) -> bytes:
    """GET with a few retries — wisteria.com throttles bursts with 403/429."""
    last = None
    for attempt in range(4):
        req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (403, 429, 503):
                time.sleep(2 * (attempt + 1))
                continue
            raise
    raise last

# product_type / title keyword → our category enum (see taxonomy.py).
# Order matters: first matching rule wins.
CATEGORY_RULES = [
    ("seating", ["chair", "sofa", "stool", "bench", "settee", "loveseat", "ottoman", "seating"]),
    ("bed", ["bed", "headboard", "nightstand"]),
    ("lighting", ["lamp", "light", "chandelier", "sconce", "pendant", "lantern",
                  "flush", "ceiling", "semi-flush"]),
    ("rug", ["rug", "carpet"]),
    ("storage", ["dresser", "sideboard", "buffet", "cabinet", "bookcase", "bookshelf",
                 "credenza", "chest", "wardrobe", "shelf", "storage", "console",
                 "media", "island"]),
    # textile & tabletop MUST precede "table": "tabletop"/"tablecloth" contain "table".
    ("textile", ["pillow", "throw", "blanket", "bedding", "duvet", "quilt", "coverlet",
                 "sham", "curtain", "drape", "textile", "linen", "towel", "napkin",
                 "tablecloth", "table runner"]),
    ("tabletop", ["tabletop", "dinnerware", "flatware", "serveware", "drinkware",
                  "glassware", "stemware", "barware", "platter", "pitcher", "decanter",
                  "tureen", "teapot", "mug", "dinner plate", "salad plate", "charger"]),
    ("table", ["table", "desk"]),
    ("decor", ["vase", "mirror", "wall", "art", "candle", "planter", "basket", "decor",
               "sculpture", "figurine", "statuary", "object", "bowl", "tray"]),
]

# Categories we try to fill (excludes the "other" catch-all).
TARGET_CATEGORIES = ["seating", "table", "storage", "bed", "lighting", "rug", "decor",
                     "textile", "tabletop"]


def categorize(product: dict) -> str:
    hay = f"{product.get('product_type', '')} {product.get('title', '')}".lower()
    for bucket, keywords in CATEGORY_RULES:
        if any(k in hay for k in keywords):
            return bucket
    return "other"


def fetch_page(page: int) -> list[dict]:
    url = f"{BASE}/products.json?limit=250&page={page}"
    return json.loads(_get(url, timeout=30)).get("products", [])


def primary_image(product: dict) -> str | None:
    # Production images are JPEG/PNG only — skip WebP so the POC matches.
    for img in product.get("images") or []:
        src = img.get("src")
        if src and os.path.splitext(src.split("?")[0])[1].lower() in (".jpg", ".jpeg", ".png"):
            return src
    return None


def load_existing(manifest_path: str) -> tuple[list[dict], set[str], dict[str, int]]:
    """Existing manifest + the handles already downloaded + per-category counts.

    Handles are recovered from each `image_path` (named `{category}_{handle}{ext}`)
    so an additive run never re-picks or re-downloads a product, and the manifest
    schema stays unchanged ({name, category, image_path}).
    """
    if not os.path.exists(manifest_path):
        return [], set(), {}
    with open(manifest_path) as f:
        existing = json.load(f)
    handles: set[str] = set()
    per_cat: dict[str, int] = {}
    for e in existing:
        stem = os.path.splitext(os.path.basename(e.get("image_path", "")))[0]
        if "_" in stem:
            handles.add(stem.split("_", 1)[1])
        per_cat[e.get("category")] = per_cat.get(e.get("category"), 0) + 1
    return existing, handles, per_cat


def download(src: str, dest: str) -> bool:
    # Ask Shopify for a demo-sized render to stay well under the 10 MB cap.
    sized = src + ("&" if "?" in src else "?") + "width=800"
    try:
        data = _get(sized, timeout=60)
        with open(dest, "wb") as f:
            f.write(data)
        return True
    except Exception as e:  # noqa: BLE001
        print(f"  download failed {src}: {e}")
        return False


def main() -> int:
    ap = argparse.ArgumentParser()
    # Additive by default: fetch this many NEW products per target category and
    # append them to the existing manifest (existing products are never touched).
    ap.add_argument("--add-per-category", type=int, default=57,
                    help="new images to add per target category (7 targets × 57 ≈ 400)")
    ap.add_argument("--max-pages", type=int, default=40)
    ap.add_argument("--out-manifest", default="../../data/catalog/products.json")
    ap.add_argument("--image-dir", default="../../data/catalog/images")
    args = ap.parse_args()

    existing, seen_handles, existing_per_cat = load_existing(args.out_manifest)
    print(f"existing catalog: {len(existing)} products "
          f"({', '.join(f'{c}={existing_per_cat.get(c, 0)}' for c in TARGET_CATEGORIES)})")

    # Collect only NEW, balanced picks for the target categories ('other' is the
    # catch-all and is left as-is so the added set stays category-balanced).
    new_buckets: dict[str, list] = {c: [] for c in TARGET_CATEGORIES}
    for page in range(1, args.max_pages + 1):
        products = fetch_page(page)
        if not products:
            break
        for p in products:
            handle = p.get("handle")
            if not handle or handle in seen_handles:
                continue
            cat = categorize(p)
            if cat not in new_buckets or len(new_buckets[cat]) >= args.add_per_category:
                continue
            src = primary_image(p)
            if not src:
                continue
            seen_handles.add(handle)
            new_buckets[cat].append({"title": p.get("title", handle), "handle": handle, "src": src})
        if all(len(new_buckets[c]) >= args.add_per_category for c in TARGET_CATEGORIES):
            break

    os.makedirs(args.image_dir, exist_ok=True)
    manifest = list(existing)   # preserve existing entries verbatim
    added_per_cat: dict[str, int] = {}
    for cat in sorted(new_buckets):
        for item in new_buckets[cat]:
            ext = os.path.splitext(item["src"].split("?")[0])[1] or ".jpg"
            fname = f"{cat}_{item['handle']}{ext}"
            if download(item["src"], os.path.join(args.image_dir, fname)):
                manifest.append({"name": item["title"], "category": cat, "image_path": fname})
                added_per_cat[cat] = added_per_cat.get(cat, 0) + 1
            time.sleep(0.2)

    os.makedirs(os.path.dirname(args.out_manifest) or ".", exist_ok=True)
    with open(args.out_manifest, "w") as f:
        json.dump(manifest, f, indent=2)

    added = sum(added_per_cat.values())
    print(f"\nadded {added} new products (target {args.add_per_category}/category) → "
          f"{len(manifest)} total in {args.out_manifest}")
    for cat in TARGET_CATEGORIES:
        got = added_per_cat.get(cat, 0)
        short = "" if got >= args.add_per_category else f"  ⚠ short {args.add_per_category - got} (site ran out)"
        print(f"  {cat:<10} +{got:<3} (now {existing_per_cat.get(cat, 0) + got}){short}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())