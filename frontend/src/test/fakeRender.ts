// Test-only fake of POST /api/render.
import type { RenderResult, ResumeTree } from '../api/types'

export interface FakeRender {
  handle(method: string, path: string, body: unknown): Response | null
  calls: ResumeTree[]
  /** Test hook: answer the next render with this result (200) or an HTTP error. */
  nextResult: RenderResult | { status: number; code: string; message: string } | null
}

export function createFakeRender(): FakeRender {
  const fake: FakeRender = {
    calls: [],
    nextResult: null,
    handle(method, path, body) {
      if (path !== '/api/render' || method !== 'POST') return null
      fake.calls.push((body as { tree: ResumeTree }).tree)
      const next = fake.nextResult
      fake.nextResult = null
      if (next && 'status' in next) return new Response(JSON.stringify({ code: next.code, message: next.message }), { status: next.status })
      const result: RenderResult = next ?? {
        success: true,
        contentHash: String(fake.calls.length).padStart(64, '0'),
        cached: false,
        pageCount: 1,
        pdfUrl: `/api/render/${String(fake.calls.length).padStart(64, '0')}/pdf`,
        log: '',
        errors: [],
      }
      return new Response(JSON.stringify(result), { status: 200 })
    },
  }
  return fake
}
