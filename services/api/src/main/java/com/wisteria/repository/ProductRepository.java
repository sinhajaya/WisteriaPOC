package com.wisteria.repository;

import com.wisteria.model.ProductEntry;
import com.wisteria.model.StyleAttributes;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * pgvector persistence via {@link NamedParameterJdbcTemplate} (LLD v2.1).
 * Vectors are bound as the pgvector string form ({@code [a,b,…]}) with an
 * explicit {@code CAST(:vec AS vector)} — no JPA entity for the vector type.
 */
@Repository
public class ProductRepository {

    private static final String UPSERT_EMBEDDING = """
            INSERT INTO product_embeddings (product_id, name, category, image_path, embedding, model_ver)
            VALUES (:id, :name, :category, :path, CAST(:vec AS vector), :modelVer)
            ON CONFLICT (product_id) DO UPDATE
               SET name = EXCLUDED.name,
                   category = EXCLUDED.category,
                   image_path = EXCLUDED.image_path,
                   embedding = EXCLUDED.embedding,
                   model_ver = EXCLUDED.model_ver,
                   indexed_at = now()
            """;

    private static final String UPSERT_ATTRIBUTES = """
            INSERT INTO product_style_attributes
                (product_id, furniture_category, finish, material, silhouette, era, palette, mood, raw_json)
            VALUES (:id, :category, :finish, :material, :silhouette, :era, :palette, :mood, :raw)
            ON CONFLICT (product_id) DO UPDATE
               SET furniture_category = EXCLUDED.furniture_category,
                   finish = EXCLUDED.finish,
                   material = EXCLUDED.material,
                   silhouette = EXCLUDED.silhouette,
                   era = EXCLUDED.era,
                   palette = EXCLUDED.palette,
                   mood = EXCLUDED.mood,
                   raw_json = EXCLUDED.raw_json,
                   indexed_at = now()
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public ProductRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** pgvector text form, e.g. {@code [0.1,0.2,…]}, bound via CAST(:vec AS vector). */
    static String toVectorLiteral(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 8 + 2).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    /** Upsert a product's embedding + attributes atomically (one product per call). */
    @Transactional
    public void upsertProduct(ProductEntry entry, UUID productId, float[] embedding,
                              String modelVer, StyleAttributes attrs) {
        String vec = toVectorLiteral(embedding);
        jdbc.update(UPSERT_EMBEDDING, new MapSqlParameterSource()
                .addValue("id", productId)
                .addValue("name", entry.name())
                .addValue("category", entry.category())
                .addValue("path", entry.imagePath())
                .addValue("vec", vec)
                .addValue("modelVer", modelVer));
        jdbc.update(UPSERT_ATTRIBUTES, new MapSqlParameterSource()
                .addValue("id", productId)
                .addValue("category", attrs.furnitureCategory())
                .addValue("finish", attrs.finish())
                .addValue("material", attrs.material())
                .addValue("silhouette", attrs.silhouette())
                .addValue("era", attrs.era())
                .addValue("palette", attrs.palette())
                .addValue("mood", attrs.mood())
                .addValue("raw", attrs.rawJson()));
    }

    public long countEmbeddings() {
        Long n = jdbc.getJdbcTemplate().queryForObject(
                "SELECT count(*) FROM product_embeddings", Long.class);
        return n == null ? 0 : n;
    }

    public List<String> distinctModelVersions() {
        return jdbc.getJdbcTemplate().queryForList(
                "SELECT DISTINCT model_ver FROM product_embeddings WHERE model_ver IS NOT NULL",
                String.class);
    }

    /**
     * Top-N ANN candidates by cosine distance, with optional exact pre-filter
     * on era/material. Wrapped in a transaction so {@code SET LOCAL
     * hnsw.ef_search} applies to the search on the same connection.
     */
    @Transactional(readOnly = true)
    public List<CandidateRow> findTopCandidates(float[] queryEmbedding, String filterEra,
                                                String filterMaterial, int limit) {
        String queryVec = toVectorLiteral(queryEmbedding);
        jdbc.getJdbcTemplate().execute("SET LOCAL hnsw.ef_search = 50");

        StringBuilder sql = new StringBuilder("""
                SELECT pe.product_id, pe.name, pe.image_path, sa.furniture_category,
                       sa.finish, sa.material, sa.silhouette, sa.era, sa.palette, sa.mood,
                       (pe.embedding <=> CAST(:vec AS vector)) AS cosine_distance
                FROM product_embeddings pe
                JOIN product_style_attributes sa ON sa.product_id = pe.product_id
                """);
        MapSqlParameterSource p = new MapSqlParameterSource().addValue("vec", queryVec);
        if (filterEra != null && !filterEra.isBlank()) {
            sql.append("WHERE sa.era = :era\n");
            p.addValue("era", filterEra);
            if (filterMaterial != null && !filterMaterial.isBlank()) {
                sql.append("AND sa.material = :material\n");
                p.addValue("material", filterMaterial);
            }
        } else if (filterMaterial != null && !filterMaterial.isBlank()) {
            sql.append("WHERE sa.material = :material\n");
            p.addValue("material", filterMaterial);
        }
        sql.append("ORDER BY cosine_distance ASC\nLIMIT :limit");
        p.addValue("limit", limit);

        return jdbc.query(sql.toString(), p, (rs, i) -> new CandidateRow(
                rs.getObject("product_id", UUID.class),
                rs.getString("name"),
                rs.getString("image_path"),
                rs.getString("furniture_category"),
                rs.getString("finish"),
                rs.getString("material"),
                rs.getString("silhouette"),
                rs.getString("era"),
                rs.getString("palette"),
                rs.getString("mood"),
                rs.getDouble("cosine_distance")));
    }
}