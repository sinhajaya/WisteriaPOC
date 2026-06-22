import { useEffect, useState } from 'react'

const STAGES = [
  'Analyzing style…',
  'Finding visual matches…',
  'Writing match notes…',
]

/** Staged copy that mirrors the backend pipeline while the request runs. */
export default function LoadingState() {
  const [stage, setStage] = useState(0)

  useEffect(() => {
    const id = setInterval(() => {
      setStage((s) => Math.min(s + 1, STAGES.length - 1))
    }, 1400)
    return () => clearInterval(id)
  }, [])

  return (
    <div className="loading">
      <div className="spinner" />
      <p className="loading-text">{STAGES[stage]}</p>
    </div>
  )
}