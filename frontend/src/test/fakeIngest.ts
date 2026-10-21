// Test-only in-memory ingest API (ingest with duplicate detection, event polling, tokens). Not bundled.
import { ApiError, type IngestApi, type IngestEvent, type IngestToken } from '../api/types'

export function createFakeIngest(): IngestApi {
  const events = new Map<number, { event: IngestEvent; polls: number; fail: boolean }>()
  const byContent = new Map<string, number>()
  const tokens: IngestToken[] = []
  let nextEvent = 1
  let nextToken = 1

  return {
    async ingest(kind, payload) {
      const key = `${kind}:${payload.trim()}`
      const existing = byContent.get(key)
      if (existing) return { ingestEventId: existing, status: events.get(existing)!.event.status, duplicate: true }
      const id = nextEvent++
      byContent.set(key, id)
      events.set(id, {
        polls: 0,
        fail: kind === 'url' && payload.includes('fail'),
        event: { id, kind, status: 'queued', receivedAt: new Date().toISOString(), finishedAt: null, jobId: null, error: null },
      })
      return { ingestEventId: id, status: 'queued', duplicate: false }
    },
    async getEvent(id) {
      const e = events.get(id)
      if (!e) throw new ApiError(404, { code: 'not_found', message: 'No such event' })
      e.polls++
      if (e.polls === 1) e.event = { ...e.event, status: 'processing' }
      else if (e.fail) e.event = { ...e.event, status: 'failed', finishedAt: new Date().toISOString(), error: { code: 'fetch_failed', message: 'The page could not be fetched' } }
      else e.event = { ...e.event, status: 'done', finishedAt: new Date().toISOString(), jobId: 100 + id }
      return { ...e.event }
    },
    async listTokens() {
      return [...tokens].reverse().map((t) => ({ ...t }))
    },
    async createToken(label) {
      const t: IngestToken = { id: nextToken++, label, createdAt: new Date().toISOString(), lastUsedAt: null, revokedAt: null }
      tokens.push(t)
      return { ...t, token: `fake-ingest-${t.id}-${Math.random().toString(36).slice(2)}` }
    },
    async revokeToken(id) {
      const t = tokens.find((x) => x.id === id)
      if (!t) throw new ApiError(404, { code: 'not_found', message: 'No such token' })
      t.revokedAt ??= new Date().toISOString()
    },
  }
}
