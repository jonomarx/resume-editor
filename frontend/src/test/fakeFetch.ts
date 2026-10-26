// Test-only: serves the contract's sources and ingest paths from in-memory implementations, so the real HTTP
// client (paths, methods, bodies, error mapping) is what the page tests run against.
import { ApiError, type IngestApi, type SourcesApi } from '../api/types'

const json = (status: number, body?: unknown) => new Response(body === undefined ? null : JSON.stringify(body), { status })

function failure(e: unknown): Response {
  if (e instanceof ApiError) {
    return json(e.status, { code: e.code, message: e.message, details: e.details, existingSourceId: e.existingSourceId })
  }
  throw e // anything else surfaces like a network error
}

/** Implementations are looked up on every request, so tests may replace methods on them. */
export function createFakeFetch(impl: { sources?: SourcesApi; ingest?: IngestApi }): typeof fetch {
  return (async (input: RequestInfo | URL, init: RequestInit = {}) => {
    const path = String(input)
    const method = init.method ?? 'GET'
    const body = init.body ? JSON.parse(init.body as string) : undefined
    const id = Number(path.split('/').pop())
    try {
      const { sources, ingest } = impl
      if (sources) {
        if (path === '/api/source-health') return json(200, await sources.health())
        if (path === '/api/sources' && method === 'GET') return json(200, await sources.list())
        if (path === '/api/sources' && method === 'POST') return json(201, await sources.create(body.url, body.name))
        if (/^\/api\/sources\/\d+$/.test(path) && method === 'PUT') return json(200, await sources.update(id, body))
        if (/^\/api\/sources\/\d+$/.test(path) && method === 'DELETE') return (await sources.remove(id), json(204))
      }
      if (ingest) {
        if (path === '/api/ingest' && method === 'POST') return json(202, await ingest.ingest(body.kind, body.payload, body.sourceHint))
        if (/^\/api\/ingest\/events\/\d+$/.test(path)) return json(200, await ingest.getEvent(id))
        if (path === '/api/ingest/tokens' && method === 'GET') return json(200, await ingest.listTokens())
        if (path === '/api/ingest/tokens' && method === 'POST') return json(201, await ingest.createToken(body.label))
        if (/^\/api\/ingest\/tokens\/\d+$/.test(path) && method === 'DELETE') return (await ingest.revokeToken(id), json(204))
      }
      if (path === '/api/me') return json(401, { code: 'unauthorized', message: 'Sign in required' })
      return json(404, { code: 'not_found', message: 'Not found' })
    } catch (e) {
      return failure(e)
    }
  }) as typeof fetch
}
