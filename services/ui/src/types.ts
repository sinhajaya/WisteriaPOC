// Mirrors com.wisteria.model.StyleQueryResponse (snake_case on the wire).

export interface QueryAttributes {
  category: string | null
  finish: string | null
  material: string | null
  silhouette: string | null
  era: string | null
  palette: string | null
  mood: string | null
}

export interface ResultItem {
  product_id: string
  name: string
  image_url: string
  similarity_score: number // 0–100
  matched_attributes: Record<string, string>
  why_matches: string | null
}

export interface StyleQueryResponse {
  query_id: string
  cache_hit: boolean
  latency_ms: number
  low_confidence: boolean
  out_of_domain: boolean // image isn't furniture — distinct from low_confidence
  vlm_degraded: boolean // VLM was unavailable — embedding-only fallback (transient system fault)
  query_attributes: QueryAttributes
  results: ResultItem[]
}

export interface QueryOptions {
  topK?: number
  filterEra?: string
  filterMaterial?: string
}