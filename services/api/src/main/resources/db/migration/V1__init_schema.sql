-- ───────────────────────────────────────────────────────────
-- Wisteria Visual Style Matching · schema (LLD v2.1, Day 1)
-- pgvector HNSW search space + 6-field style attributes + query log.
-- ───────────────────────────────────────────────────────────

CREATE EXTENSION IF NOT EXISTS vector;

-- Visual fingerprints (CLIP ViT-L/14, 768-dim, L2-normalised).
CREATE TABLE product_embeddings (
    product_id  UUID PRIMARY KEY,
    name        VARCHAR(255),
    category    VARCHAR(100),
    image_path  VARCHAR(512),
    embedding   vector(768),       -- CLIP ViT-L/14 output
    model_ver   VARCHAR(64),       -- MUST match between indexing and query
    indexed_at  TIMESTAMPTZ DEFAULT now()
);

-- Explicit HNSW build parameters (v2.1 fix: v2.0 used silent defaults).
CREATE INDEX product_embeddings_hnsw_idx
    ON product_embeddings
    USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- Structured style DNA extracted by Claude Vision (closed enum vocab).
CREATE TABLE product_style_attributes (
    product_id  UUID PRIMARY KEY REFERENCES product_embeddings (product_id) ON DELETE CASCADE,
    finish      VARCHAR(64),       -- matte | gloss | brushed | aged | lacquered | natural
    material    VARCHAR(64),       -- oak | walnut | velvet | brass | linen | marble | rattan | glass | leather | ceramic
    silhouette  VARCHAR(64),       -- clean-line | curved | ornate | sculptural | minimal
    era         VARCHAR(64),       -- mid-century | art-deco | japandi | contemporary | industrial | traditional
    palette     VARCHAR(64),       -- warm-neutral | cool-neutral | earthy | monochrome | bold
    mood        VARCHAR(64),       -- cosy | editorial | calm | dramatic | playful
    raw_json    TEXT,              -- full Claude response, future-proofing
    indexed_at  TIMESTAMPTZ DEFAULT now()
);

-- Per-query analytics: match quality + latency + cache effectiveness.
CREATE TABLE style_query_log (
    query_id    UUID PRIMARY KEY,
    phash       BIGINT,
    latency_ms  INTEGER,
    cache_hit   BOOLEAN,
    top_score   INTEGER,
    created_at  TIMESTAMPTZ DEFAULT now()
);