import { createDummySources } from '../dummy/dummySources'
import { httpApi } from './http'
import type { Api } from './types'

// DUMMY(US-032, US-043): sources use the in-memory stand-in until the real endpoints are ready;
// VITE_USE_REAL_SOURCES=true switches to them.
export const api: Api = import.meta.env.VITE_USE_REAL_SOURCES === 'true' ? httpApi : { ...httpApi, sources: createDummySources() }

export * from './types'
