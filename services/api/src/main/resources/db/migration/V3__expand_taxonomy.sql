-- LLD v3.0 taxonomy expansion, driven by the real Wisteria catalog.
-- material += teak, wood, iron, cane, bone
-- era/style += organic-modern, coastal, bohemian
--
-- The attribute columns are VARCHAR(64) with NO CHECK constraint: the closed
-- vocabulary is enforced upstream (Claude tool enum / VLM coercion in
-- taxonomy.py + vlm-server), and a hard DB CHECK would fail-close on model
-- drift instead of degrading. This migration documents the authoritative
-- vocabulary as column comments so the contract lives in the schema too.

COMMENT ON COLUMN product_style_attributes.furniture_category IS
    'seating | table | storage | bed | lighting | rug | decor | other';
COMMENT ON COLUMN product_style_attributes.finish IS
    'matte | gloss | brushed | aged | lacquered | natural';
COMMENT ON COLUMN product_style_attributes.material IS
    'oak | walnut | velvet | brass | linen | marble | rattan | glass | leather | ceramic | teak | wood | iron | cane | bone';
COMMENT ON COLUMN product_style_attributes.silhouette IS
    'clean-line | curved | ornate | sculptural | minimal';
COMMENT ON COLUMN product_style_attributes.era IS
    'mid-century | art-deco | japandi | contemporary | industrial | traditional | organic-modern | coastal | bohemian';
COMMENT ON COLUMN product_style_attributes.palette IS
    'warm-neutral | cool-neutral | earthy | monochrome | bold';
COMMENT ON COLUMN product_style_attributes.mood IS
    'cosy | editorial | calm | dramatic | playful';