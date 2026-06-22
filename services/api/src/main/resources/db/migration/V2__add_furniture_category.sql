-- LLD v2.1 refinement: capture the KIND of furniture so matching can rank
-- same-category items first (a sideboard query should beat chairs).
-- Coarse, closed vocabulary extracted by Claude for both catalog and query.
ALTER TABLE product_style_attributes
    ADD COLUMN furniture_category VARCHAR(64);   -- seating | table | storage | bed | lighting | rug | decor | other
