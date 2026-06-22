import { useState } from 'react'
import type { ResultItem } from '../../types'
import SimilarityBadge from './SimilarityBadge'

interface Props {
  item: ResultItem
}

export default function ProductCard({ item }: Props) {
  const [imgError, setImgError] = useState(false)
  const matched = Object.entries(item.matched_attributes ?? {})

  return (
    <article className="card">
      <div className="card-media">
        {imgError ? (
          <div className="card-media-fallback">No image</div>
        ) : (
          <img src={item.image_url} alt={item.name} loading="lazy" onError={() => setImgError(true)} />
        )}
        <SimilarityBadge score={item.similarity_score} />
      </div>
      <div className="card-body">
        <h3 className="card-title">{item.name}</h3>
        {matched.length > 0 && (
          <div className="pills small">
            {matched.map(([k, v]) => (
              <span key={k} className="pill matched">
                <span className="pill-key">{k}</span>
                {v}
              </span>
            ))}
          </div>
        )}
        {item.why_matches && <p className="card-why">{item.why_matches}</p>}
      </div>
    </article>
  )
}