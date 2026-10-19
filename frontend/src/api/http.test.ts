import { afterEach, beforeEach, vi } from 'vitest'
import { createFakeServer } from '../test/fakeServer'
import { createHttpApi, readCsrfCookie } from './http'

const creds = { username: 'ada', password: 'correct-horse' }

beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/'
})
afterEach(() => vi.unstubAllGlobals())

function spied() {
  const server = createFakeServer()
  const calls: { method: string; path: string; header: string | null }[] = []
  const f = vi.fn(async (input: RequestInfo | URL, init: RequestInit = {}) => {
    calls.push({ method: init.method ?? 'GET', path: String(input), header: new Headers(init.headers).get('X-XSRF-TOKEN') })
    return server.fetch(input, init)
  }) as unknown as typeof fetch
  return { server, calls, api: createHttpApi(f) }
}

describe('csrf handling', () => {
  it('fetches a token via GET /api/me before the first mutating call, and sends the header only on mutations', async () => {
    const { api, calls } = spied()
    await api.auth.signUp(creds)
    expect(calls[0]).toMatchObject({ method: 'GET', path: '/api/me', header: null })
    expect(calls[1]).toMatchObject({ method: 'POST', path: '/api/auth/signup' })
    expect(calls[1].header).toBe('fake-token-1')
    await api.auth.getMe()
    expect(calls.at(-1)).toMatchObject({ method: 'GET', header: null })
  })

  it('re-reads the cookie after login replaces it', async () => {
    const { api, calls } = spied()
    await api.auth.signUp(creds)
    await api.auth.logOut()
    expect(calls.at(-1)!.header).toBe(readCsrfCookie())
    await api.auth.logIn(creds)
    await api.auth.logOut()
    expect(calls.at(-1)!.header).toBe(readCsrfCookie())
  })

  it('retries once after csrf_invalid with a refreshed token', async () => {
    const { api, calls, server } = spied()
    await api.auth.signUp(creds)
    server.rotateToken()
    await api.auth.logOut()
    const posts = calls.filter((c) => c.path === '/api/auth/logout')
    expect(posts).toHaveLength(2)
  })

  it('surfaces csrf_invalid when the retry also fails', async () => {
    const f = vi.fn(async (_i: RequestInfo | URL, init: RequestInit = {}) =>
      init.method === 'GET'
        ? new Response(null, { status: 401 })
        : new Response(JSON.stringify({ code: 'csrf_invalid', message: 'bad' }), { status: 403 }),
    ) as unknown as typeof fetch
    await expect(createHttpApi(f).auth.logIn(creds)).rejects.toMatchObject({ status: 403, code: 'csrf_invalid' })
    expect(vi.mocked(f).mock.calls.filter(([, i]) => i?.method === 'POST')).toHaveLength(2)
  })

  it('does not use web storage', async () => {
    const set = vi.spyOn(Storage.prototype, 'setItem')
    const { api } = spied()
    await api.auth.signUp(creds)
    expect(set).not.toHaveBeenCalled()
  })

  it('maps error bodies and 409 to ApiError', async () => {
    const { api } = spied()
    await api.auth.signUp(creds)
    await expect(api.auth.signUp({ ...creds, username: 'ADA' })).rejects.toMatchObject({ status: 409, code: 'username_taken' })
  })

  it('getMe returns null when signed out', async () => {
    expect(await spied().api.auth.getMe()).toBeNull()
  })
})
