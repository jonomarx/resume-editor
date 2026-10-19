import { dummyApi } from '../dummy/dummyApi'
import { httpApi } from './http'
import type { Api } from './types'

// DUMMY(US-006): VITE_USE_DUMMY_API=true selects the in-memory API until the real auth endpoints are ready.
export const api: Api = import.meta.env.VITE_USE_DUMMY_API === 'true' ? dummyApi : httpApi

export * from './types'
