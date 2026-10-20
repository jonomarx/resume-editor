import { vi } from 'vitest'
import { INGEST_LABELS, INGEST_MAX_PAYLOAD_CHARS } from '../config/constants'
import { bookmarkletUrl, buildBookmarkletCode } from './bookmarklet'

type FetchStub = ReturnType<typeof vi.fn>

/** Runs the generated code the way the browser would, with the page's globals replaced by stubs. */
async function run(code: string, opts: { html?: string; href?: string; fetch: FetchStub }) {
  const alert = vi.fn()
  const document = { documentElement: { outerHTML: opts.html ?? '<html></html>' } }
  const location = { href: opts.href ?? 'https://jobs.example/p/1' }
  new Function('fetch', 'alert', 'document', 'location', code)(opts.fetch, alert, document, location)
  await new Promise((r) => setTimeout(r, 0))
  return alert
}

const first = (f: FetchStub) => f.mock.calls[0] as unknown as [string, RequestInit]
const ok = () => vi.fn(async () => ({ ok: true, status: 202 }))

describe('bookmarklet', () => {
  it('posts the page html and url to the ingest endpoint with the bearer token only', async () => {
    const fetch = ok()
    const alert = await run(buildBookmarkletCode('https://app.example', 'tok-123'), { fetch, html: '<html>hi</html>', href: 'https://jobs.example/p/1' })
    expect(fetch).toHaveBeenCalledTimes(1)
    const [url, init] = first(fetch)
    expect(url).toBe('https://app.example/api/ingest')
    expect(init.method).toBe('POST')
    expect(init.headers).toEqual({ Authorization: 'Bearer tok-123', 'Content-Type': 'application/json' })
    expect(init).not.toHaveProperty('credentials')
    expect(JSON.parse(init.body as string)).toEqual({ kind: 'html', payload: '<html>hi</html>', sourceHint: 'https://jobs.example/p/1' })
    expect(alert).toHaveBeenCalledWith(INGEST_LABELS.alertSent)
  })

  it('truncates oversized pages and long urls to what the API accepts', async () => {
    const fetch = ok()
    await run(buildBookmarkletCode('https://app.example', 't'), { fetch, html: 'x'.repeat(INGEST_MAX_PAYLOAD_CHARS + 10), href: 'https://a.example/' + 'y'.repeat(900) })
    const body = JSON.parse(first(fetch)[1].body as string)
    expect(body.payload).toHaveLength(INGEST_MAX_PAYLOAD_CHARS)
    expect(body.sourceHint.length).toBeLessThanOrEqual(500)
  })

  it('reports a rejection with its status and a network failure', async () => {
    const rejected = await run(buildBookmarkletCode('https://app.example', 't'), { fetch: vi.fn(async () => ({ ok: false, status: 401 })) })
    expect(rejected).toHaveBeenCalledWith(INGEST_LABELS.alertFailed(401))
    const offline = await run(buildBookmarkletCode('https://app.example', 't'), { fetch: vi.fn(async () => Promise.reject(new TypeError('x'))) })
    expect(offline).toHaveBeenCalledWith(INGEST_LABELS.alertUnreachable)
  })

  it('treats hostile token and origin text as data, never as code', async () => {
    const evil = `"+alert('pwned')+"'\\\`</script>\n`
    const fetch = ok()
    const alert = await run(buildBookmarkletCode(`https://app.example/${evil}`, evil), { fetch })
    const [url, init] = first(fetch)
    expect((init.headers as Record<string, string>).Authorization).toBe('Bearer ' + evil)
    expect(url).toBe(`https://app.example/${evil}/api/ingest`)
    expect(alert).toHaveBeenCalledTimes(1)
    expect(alert).toHaveBeenCalledWith(INGEST_LABELS.alertSent)
  })

  it('uses no cookies or csrf header and survives URL encoding', () => {
    const code = buildBookmarkletCode('https://app.example', 'tok')
    expect(code).not.toMatch(/cookie|XSRF|credentials/i)
    const url = bookmarkletUrl(code)
    expect(url.startsWith('javascript:')).toBe(true)
    expect(decodeURIComponent(url.slice('javascript:'.length))).toBe(code)
  })
})
