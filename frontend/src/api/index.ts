import { createDummyIngest } from '../dummy/dummyIngest'
import { createDummySources } from '../dummy/dummySources'
import { httpApi } from './http'
import type { Api } from './types'

// DUMMY(US-032, US-043): sources use the in-memory stand-in until the real endpoints are ready;
// VITE_USE_REAL_SOURCES=true switches to them.
export const api: Api = {
  ...httpApi,
  sources: import.meta.env.VITE_USE_REAL_SOURCES === 'true' ? httpApi.sources : createDummySources(),
  // DUMMY(US-041): ingest uses the in-memory stand-in until the real endpoint is ready; VITE_USE_REAL_INGEST=true switches.
  ingest: import.meta.env.VITE_USE_REAL_INGEST === 'true' ? httpApi.ingest : createDummyIngest(),
}

export * from './types'
