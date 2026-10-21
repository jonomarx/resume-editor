import { createDummyIngest } from '../dummy/dummyIngest'
import { httpApi } from './http'
import type { Api } from './types'

export const api: Api = {
  ...httpApi,
  // DUMMY(US-041): ingest uses the in-memory stand-in until the real endpoint is ready; VITE_USE_REAL_INGEST=true switches.
  ingest: import.meta.env.VITE_USE_REAL_INGEST === 'true' ? httpApi.ingest : createDummyIngest(),
}

export * from './types'
