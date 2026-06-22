# Catalog drop-in folder

Put your product **images** in this folder and list them in **`products.json`**.
The indexer reads from here directly (filesystem), so you can change images and
re-index **without rebuilding** the app.

## Steps
1. Copy your product images here, e.g. `rattan-lounge.jpg`, `walnut-sideboard.jpg`
   (JPEG or PNG; WebP needs a codec and may be rejected by the Java layer).
2. Edit `products.json` — one entry per product:
   ```json
   {
     "product_id": "optional-uuid (auto-generated if omitted)",
     "name": "Rattan Lounge Chair",
     "category": "seating",
     "image_path": "rattan-lounge.jpg"   // filename relative to THIS folder
   }
   ```
   Do **not** set style attributes (material/era/…) — Claude extracts those at index time.
3. Tell Claude Code to run the index. The app is launched with:
   - `CATALOG_PRODUCTS_FILE=file:<repo>/data/catalog/products.json`
   - `CATALOG_IMAGE_DIR=file:<repo>/data/catalog/`
   - `ANTHROPIC_API_KEY` from the repo-root `.env`

`products.json` here ships with two placeholder rows — replace them with your real catalog.