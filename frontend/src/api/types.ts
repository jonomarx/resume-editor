import type { components } from './schema'

export type User = components['schemas']['User']
export type Credentials = components['schemas']['Credentials']
export type ApiErrorBody = components['schemas']['Error']

/** Error thrown by any API client call; mirrors the contract's Error schema. */
export class ApiError extends Error {
  readonly status: number
  readonly code: string

  constructor(status: number, body: ApiErrorBody) {
    super(body.message)
    this.name = 'ApiError'
    this.status = status
    this.code = body.code
  }
}

/** Auth operations (openapi operationIds signUp, logIn, logOut, getMe). */
export interface AuthApi {
  signUp(credentials: Credentials): Promise<User>
  logIn(credentials: Credentials): Promise<User>
  logOut(): Promise<void>
  /** Resolves to null when nobody is signed in (401). */
  getMe(): Promise<User | null>
}

export interface Api {
  auth: AuthApi
}
