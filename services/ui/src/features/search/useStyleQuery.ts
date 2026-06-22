import { useCallback, useState } from 'react'
import { queryStyle } from '../../api'
import type { QueryOptions, StyleQueryResponse } from '../../types'

export interface StyleQueryState {
  loading: boolean
  error: string | null
  result: StyleQueryResponse | null
  run: (file: File, opts?: QueryOptions) => Promise<void>
  reset: () => void
}

/** Owns loading / error / result state for a style query. */
export function useStyleQuery(): StyleQueryState {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [result, setResult] = useState<StyleQueryResponse | null>(null)

  const run = useCallback(async (file: File, opts: QueryOptions = {}) => {
    setLoading(true)
    setError(null)
    setResult(null)
    try {
      setResult(await queryStyle(file, opts))
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Something went wrong')
    } finally {
      setLoading(false)
    }
  }, [])

  const reset = useCallback(() => {
    setError(null)
    setResult(null)
  }, [])

  return { loading, error, result, run, reset }
}