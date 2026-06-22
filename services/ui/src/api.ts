import type { QueryOptions, StyleQueryResponse } from './types'

/** POST /api/v1/style/query — multipart image in, ranked products out. */
export async function queryStyle(
  file: File,
  opts: QueryOptions = {},
): Promise<StyleQueryResponse> {
  const form = new FormData()
  form.append('image', file)
  if (opts.topK) form.append('top_k', String(opts.topK))
  if (opts.filterEra) form.append('filter_era', opts.filterEra)
  if (opts.filterMaterial) form.append('filter_material', opts.filterMaterial)

  const res = await fetch('/api/v1/style/query', { method: 'POST', body: form })
  if (!res.ok) {
    throw new Error(await errorMessage(res))
  }
  return res.json()
}

/** GET /api/v1/style/health — dependency status map. */
export async function checkHealth(): Promise<Record<string, string>> {
  const res = await fetch('/api/v1/style/health')
  return res.json()
}

async function errorMessage(res: Response): Promise<string> {
  try {
    const body = await res.json()
    if (res.status === 503 && body.error === 'embedding_timeout') {
      return 'The visual matching service is busy. Please try again in a moment.'
    }
    return body.detail || body.error || `Request failed (HTTP ${res.status})`
  } catch {
    return `Request failed (HTTP ${res.status})`
  }
}