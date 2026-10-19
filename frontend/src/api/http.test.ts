import { afterEach, vi } from 'vitest'
import { httpApi } from './http'
import { ApiError } from './types'

afterEach(() => vi.unstubAllGlobals())

function stubFetch(status: number, body?: unknown) {
  const fn = vi.fn(async () => new Response(body === undefined ? null : JSON.stringify(body), { status }))
  vi.stubGlobal('fetch', fn)
  return fn
}

describe('httpApi.auth', () => {
  it('posts credentials to the login path', async () => {
    const fn = stubFetch(200, { id: 1, username: 'ada' })
    const u = await httpApi.auth.logIn({ username: 'ada', password: 'correct-horse' })
    expect(u.username).toBe('ada')
    expect(fn).toHaveBeenCalledWith('/api/auth/login', expect.objectContaining({ method: 'POST' }))
  })

  it('maps error bodies to ApiError', async () => {
    stubFetch(409, { code: 'username_taken', message: 'That username is taken' })
    await expect(httpApi.auth.signUp({ username: 'ada', password: 'correct-horse' })).rejects.toMatchObject({
      status: 409,
      code: 'username_taken',
    })
    expect(new ApiError(1, { code: 'x', message: 'y' })).toBeInstanceOf(Error)
  })

  it('getMe returns null on 401', async () => {
    stubFetch(401, { code: 'unauthorized', message: 'no' })
    expect(await httpApi.auth.getMe()).toBeNull()
  })
})
