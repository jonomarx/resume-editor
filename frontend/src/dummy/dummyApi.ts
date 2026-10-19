// DUMMY(US-006): in-memory stand-in for the auth endpoints. Delete when US-006 is ready.
import { ApiError, type Api, type Credentials, type User } from '../api/types'

export function createDummyApi(): Api {
  const accounts = new Map<string, { id: number; password: string }>()
  let nextId = 1
  let current: User | null = null

  function validate(c: Credentials) {
    if (c.username.length < 3 || c.username.length > 40 || c.password.length < 8) {
      throw new ApiError(400, { code: 'validation_failed', message: 'Username or password is invalid' })
    }
  }

  return {
    auth: {
      async signUp(c) {
        validate(c)
        if (accounts.has(c.username)) {
          throw new ApiError(409, { code: 'username_taken', message: 'That username is taken' })
        }
        const id = nextId++
        accounts.set(c.username, { id, password: c.password })
        current = { id, username: c.username }
        return current
      },
      async logIn(c) {
        const a = accounts.get(c.username)
        if (!a || a.password !== c.password) {
          throw new ApiError(401, { code: 'invalid_credentials', message: 'Wrong username or password' })
        }
        current = { id: a.id, username: c.username }
        return current
      },
      async logOut() {
        current = null
      },
      async getMe() {
        return current
      },
    },
  }
}

export const dummyApi = createDummyApi()
