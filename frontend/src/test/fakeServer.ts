// Test-only fake of the auth endpoints, including the CSRF cookie and header rules of the contract.
// It is a fake fetch, so the real HTTP client is what runs against it. Not bundled into the app.
import { createHttpApi } from '../api/http'
import type { Api, Credentials } from '../api/types'
import { AUTH, CSRF } from '../config/constants'
import { createFakeRender, type FakeRender } from './fakeRender'
import { createFakeTags, type FakeTags } from './fakeTags'
import { createFakeResume, type FakeResume } from './fakeResume'

export interface FakeServer {
  fetch: typeof fetch
  /** Test hook: invalidate the current token as if the server had rotated it. */
  rotateToken(): void
  resume: FakeResume
  render: FakeRender
  tags: FakeTags
}

export function createFakeServer(): FakeServer {
  const accounts = new Map<string, { id: number; username: string; password: string }>()
  let nextId = 1
  let currentUserId: number | null = null
  let token = ''
  let counter = 0
  const resume = createFakeResume()
  const render = createFakeRender()
  const tags = createFakeTags()

  const issueToken = () => {
    token = `fake-token-${++counter}`
    document.cookie = `${CSRF.cookieName}=${token}; path=/`
  }
  const reply = (status: number, body?: unknown) =>
    new Response(body === undefined ? null : JSON.stringify(body), { status })
  const err = (status: number, code: string, message: string) => reply(status, { code, message })

  async function handle(path: string, init: RequestInit): Promise<Response> {
    const method = init.method ?? 'GET'
    const headers = new Headers(init.headers)
    if (method === 'GET' && path === CSRF.tokenPath) {
      issueToken()
      const u = [...accounts.values()].find((a) => a.id === currentUserId)
      return u ? reply(200, { id: u.id, username: u.username }) : err(401, 'unauthorized', 'Sign in required')
    }
    if (method !== 'GET' && headers.get(CSRF.headerName) !== token) {
      return err(403, CSRF.invalidCode, 'Missing or invalid CSRF token')
    }
    const parsed = init.body ? JSON.parse(init.body as string) : undefined
    const resumeReply = resume.handle(method, path, parsed)
    if (resumeReply) return resumeReply
    const renderReply = render.handle(method, path, parsed)
    if (renderReply) return renderReply
    const tagsReply = tags.handle(method, path, parsed)
    if (tagsReply) return tagsReply
    const body = parsed as Credentials | undefined
    if (path === '/api/auth/logout') {
      currentUserId = null
      return reply(204)
    }
    if (!body || !new RegExp(AUTH.usernamePattern).test(body.username) || body.password.length < AUTH.passwordMinLength) {
      return err(400, 'validation_failed', 'Username or password is invalid')
    }
    const key = body.username.toLowerCase()
    if (path === '/api/auth/signup') {
      if (accounts.has(key)) return err(409, 'username_taken', 'That username is taken')
      const a = { id: nextId++, username: body.username, password: body.password }
      accounts.set(key, a)
      currentUserId = a.id
      issueToken()
      return reply(201, { id: a.id, username: a.username })
    }
    if (path === '/api/auth/login') {
      const a = accounts.get(key)
      if (!a || a.password !== body.password) return err(401, 'bad_credentials', 'Invalid username or password')
      currentUserId = a.id
      issueToken()
      return reply(200, { id: a.id, username: a.username })
    }
    return err(404, 'not_found', 'Not found')
  }

  return {
    fetch: ((input: RequestInfo | URL, init: RequestInit = {}) => handle(String(input), init)) as typeof fetch,
    resume,
    render,
    tags,
    rotateToken: () => {
      token = `rotated-${++counter}`
    },
  }
}

export function createFakeApi(): Api {
  return createHttpApi(createFakeServer().fetch)
}

