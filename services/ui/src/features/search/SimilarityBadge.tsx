interface Props {
  score: number // 0–100
}

function tier(score: number): string {
  if (score >= 75) return 'strong'
  if (score >= 50) return 'good'
  return 'weak'
}

export default function SimilarityBadge({ score }: Props) {
  return (
    <span className={`badge badge-${tier(score)}`} title="Similarity score">
      {score}
      <span className="badge-unit">match</span>
    </span>
  )
}