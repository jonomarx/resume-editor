import { httpApi } from './http'
import { createDummyJobsApi } from '../dummy/jobs'
import type { Api } from './types'

// DUMMY(US-050): VITE_DUMMY_JOBS=true serves the job feed from memory until the jobs endpoints exist. Delete with the swap card.
export const api: Api = import.meta.env.VITE_DUMMY_JOBS === 'true' ? { ...httpApi, jobs: createDummyJobsApi() } : httpApi

export * from './types'
