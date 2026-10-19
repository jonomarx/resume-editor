import { CSRF } from '../config/constants'
import { ApiError, type Api, type ApiErrorBody, type User } from './types'

const MUTATING = new Set(['POST', 'PUT', 'DELETE'])

/** Re-reads the XSRF-TOKEN cookie on every call (the server may replace it when a session starts). */
export function readCsrfCookie(): string | null {
  for (const part of document.cookie.split('; ')) {
    const eq = part.indexOf('=')
    if (eq > 0 && part.slice(0, eq) === CSRF.cookieName) return decodeURIComponent(part.slice(eq + 1))
  }
  return null
}

async function toError(res: Response): Promise<ApiError> {
  let body: ApiErrorBody
  try {
    body = (await res.json()) as ApiErrorBody
  } catch {
    body = { code: 'unknown_error', message: `Request failed (${res.status})` }
  }
  return new ApiError(res.status, body)
}

/**
 * Builds the API client on top of a fetch implementation. CSRF handling is middleware here, not a
 * per-call argument: mutating requests carry the cookie's token as a header, fetching a token first
 * (GET /api/me) when none is present, and one csrf_invalid 403 is retried after refreshing the token.
 * The token lives only in the cookie; nothing is written to web storage.
 */
export function createHttpApi(fetchImpl: typeof fetch = (...a) => fetch(...a)): Api {
  const send = (method: string, path: string, body: unknown, token: string | null) => {
    const headers: Record<string, string> = {}
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    if (token) headers[CSRF.headerName] = token
    return fetchImpl(path, {
      method,
      credentials: 'same-origin',
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  }

  async function refreshToken(): Promise<string | null> {
    await send('GET', CSRF.tokenPath, undefined, null)
    return readCsrfCookie()
  }

  async function request(method: string, path: string, body?: unknown): Promise<Response> {
    if (!MUTATING.has(method)) return send(method, path, body, null)
    const res = await send(method, path, body, readCsrfCookie() ?? (await refreshToken()))
    if (res.status !== 403) return res
    const code = (await res.clone().json().catch(() => null))?.code
    if (code !== CSRF.invalidCode) return res
    return send(method, path, body, await refreshToken())
  }

  async function json<T>(res: Response): Promise<T> {
    if (!res.ok) throw await toError(res)
    return (await res.json()) as T
  }

  return {
    auth: {
      signUp: async (c) => json<User>(await request('POST', '/api/auth/signup', c)),
      logIn: async (c) => json<User>(await request('POST', '/api/auth/login', c)),
      async logOut() {
        const res = await request('POST', '/api/auth/logout')
        if (!res.ok && res.status !== 401) throw await toError(res)
      },
      async getMe() {
        const res = await request('GET', CSRF.tokenPath)
        if (res.status === 401) return null
        return json<User>(res)
      },
    },
  }
}

/** Real client: talks to the Spring API through the same-origin /api path. */
export const httpApi: Api = createHttpApi()
