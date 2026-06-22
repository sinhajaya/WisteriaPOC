import type { StyleQueryResponse } from '../../types'
import ProductCard from './ProductCard'

interface Props {
  response: StyleQueryResponse
}

export default function ResultsGrid({ response }: Props) {
  // Out-of-domain: the image isn't furniture. Distinct state — no "closest
  // matches" banner, no score cards.
  if (response.out_of_domain) {
    return (
      <section className="results">
        <p className="empty out-of-domain">
          That doesn’t look like a furniture image. Try a photo of a piece of
          furniture — a chair, sofa, table, lamp, or similar.
        </p>
      </section>
    )
  }

  if (response.results.length === 0) {
    return <p className="empty">No matches found for this image.</p>
  }

  return (
    <section className="results">
      <header className="results-head">
        <h2>{response.low_confidence ? 'Closest matches we found' : 'Top matches'}</h2>
        <span className="results-meta">
          {response.results.length} results · {response.latency_ms} ms
          {response.cache_hit && ' · cached'}
        </span>
      </header>
      {/* VLM-degraded takes precedence over generic low-confidence: it's a
          transient system fault, not a verdict on the image. */}
      {response.vlm_degraded ? (
        <p className="low-confidence degraded">
          Our style analysis is temporarily unavailable, so these are matched on
          visual similarity alone. Try again shortly for a more precise result.
        </p>
      ) : (
        response.low_confidence && (
          <p className="low-confidence">
            We couldn’t find a strong match for this image, so these are the closest in style.
          </p>
        )
      )}
      <div className="grid">
        {response.results.map((item) => (
          <ProductCard key={item.product_id} item={item} />
        ))}
      </div>
    </section>
  )
}