import { ApiError, type Api, type ApiErrorBody, type User } from './types'

async function request(method: string, path: string, body?: unknown): Promise<Response> {
  return fetch(path, {
    method,
    credentials: 'same-origin',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
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

async function json<T>(res: Response): Promise<T> {
  if (!res.ok) throw await toError(res)
  return (await res.json()) as T
}

/** Real client: talks to the Spring API through the same-origin /api path. */
export const httpApi: Api = {
  auth: {
    signUp: async (c) => json<User>(await request('POST', '/api/auth/signup', c)),
    logIn: async (c) => json<User>(await request('POST', '/api/auth/login', c)),
    async logOut() {
      const res = await request('POST', '/api/auth/logout')
      if (!res.ok && res.status !== 401) throw await toError(res)
    },
    async getMe() {
      const res = await request('GET', '/api/me')
      if (res.status === 401) return null
      return json<User>(res)
    },
  },
}
