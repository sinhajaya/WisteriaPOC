import type { QueryAttributes } from '../types'

const ORDER: (keyof QueryAttributes)[] = [
  'category',
  'palette',
  'material',
  'finish',
  'silhouette',
  'era',
  'mood',
]

interface Props {
  attributes: QueryAttributes
}

/** Shows the style DNA Claude detected — builds user trust in the match. */
export default function AttributePills({ attributes }: Props) {
  const present = ORDER.filter((k) => attributes[k])
  if (present.length === 0) return null

  return (
    <div className="attr-block">
      <span className="attr-label">Detected style</span>
      <div className="pills">
        {present.map((k) => (
          <span key={k} className="pill">
            <span className="pill-key">{k}</span>
            {attributes[k]}
          </span>
        ))}
      </div>
    </div>
  )
}