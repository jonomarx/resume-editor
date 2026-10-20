// DUMMY(US-032, US-043): in-memory stand-in for the source CRUD and source-health endpoints. Delete when they are ready.
import { ApiError, type CrawlRun, type Source, type SourceHealth, type SourcesApi } from '../api/types'

const run = (id: number, status: CrawlRun['status'], error: string | null = null): CrawlRun => ({
  id,
  startedAt: '2026-10-20T06:00:00Z',
  finishedAt: '2026-10-20T06:00:09Z',
  status,
  postingsSeen: status === 'succeeded' ? 13 : 0,
  added: status === 'succeeded' ? 2 : 0,
  updated: status === 'succeeded' ? 1 : 0,
  closed: 0,
  error,
})

/** Follows the contract's normalization closely enough for duplicate detection (see createSource). */
function normalize(raw: string): string {
  const u = new URL(raw)
  for (const k of [...u.searchParams.keys()]) {
    if (/^utm_/.test(k) || ['gclid', 'fbclid', 'mc_cid', 'mc_eid', 'ref', 'ref_src'].includes(k)) u.searchParams.delete(k)
  }
  u.searchParams.sort()
  const path = u.pathname === '/' ? '/' : u.pathname.replace(/\/+$/, '')
  return `${u.protocol}//${u.host}${path}${u.search}`
}

export function createDummySources(): SourcesApi {
  const sources: Source[] = [
    { id: 1, name: 'Acme Careers', url: 'https://acme.example/careers', enabled: true, extractionTier: 'json_ld', createdAt: '2026-10-19T10:00:00Z', lastCrawlAt: '2026-10-20T06:00:00Z' },
    { id: 2, name: 'Globex Jobs', url: 'https://globex.example/jobs', enabled: true, extractionTier: 'json_ld', createdAt: '2026-10-19T11:00:00Z', lastCrawlAt: '2026-10-20T06:00:00Z' },
  ]
  const runs = new Map<number, CrawlRun[]>([
    [1, [run(41, 'succeeded')]],
    [2, [run(43, 'failed', 'fetch_failed'), run(42, 'failed', 'fetch_failed')]],
  ])
  let nextId = 3
  const find = (id: number) => {
    const s = sources.find((x) => x.id === id)
    if (!s) throw new ApiError(404, { code: 'not_found', message: 'No such source' })
    return s
  }

  return {
    async list() {
      return sources.map((s) => ({ ...s }))
    },
    async create(url, name) {
      let normalized: string
      try {
        if (!/^https?:\/\/\S+$/.test(url)) throw new Error()
        const u = new URL(url)
        if (u.hostname === 'localhost' || /^(127\.|10\.|192\.168\.|169\.254\.)/.test(u.hostname)) throw new Error()
        normalized = normalize(url)
      } catch {
        throw new ApiError(400, { code: 'validation_failed', message: 'Request failed validation', details: [{ field: 'url', message: 'must be an absolute http or https URL of a public host' }] })
      }
      const existing = sources.find((s) => s.url === normalized)
      if (existing) throw new ApiError(409, { code: 'source_exists', message: 'You already have this source', existingSourceId: existing.id })
      const s: Source = { id: nextId++, name: name || new URL(normalized).host, url: normalized, enabled: true, extractionTier: null, createdAt: new Date().toISOString(), lastCrawlAt: null }
      sources.push(s)
      return { ...s }
    },
    async update(id, changes) {
      const s = find(id)
      if (changes.name !== undefined) s.name = changes.name
      if (changes.enabled !== undefined) s.enabled = changes.enabled
      return { ...s }
    },
    async remove(id) {
      find(id)
      sources.splice(sources.findIndex((s) => s.id === id), 1)
    },
    async health(): Promise<SourceHealth[]> {
      return sources.map((s) => {
        const r = runs.get(s.id) ?? []
        const failures = r.findIndex((x) => x.status !== 'failed')
        const consecutive = failures === -1 ? r.length : failures
        return {
          source: { ...s },
          state: r.length === 0 ? 'never_run' : consecutive >= 2 ? 'failing' : consecutive === 1 ? 'degraded' : 'healthy',
          consecutiveFailures: consecutive,
          activePostings: r.length && consecutive === 0 ? 12 : 0,
          stalePostings: r.length && consecutive === 0 ? 1 : 0,
          lastRun: r[0] ?? null,
          recentRuns: r,
        }
      })
    },
  }
}
