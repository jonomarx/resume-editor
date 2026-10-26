// DUMMY(US-050): in-memory stand-in for GET /api/jobs and PUT /api/jobs/{id}/status. Fit scores are a
// DUMMY(US-051) too: the share of the posting's tags found in DUMMY_MASTER_TAGS. Delete this file when the real endpoints land.
import type { JobQuery, JobSummary, JobsApi } from '../api/types'

const DUMMY_MASTER_TAGS = ['java', 'react', 'python']
const DAY_MS = 86_400_000

type Seed = Omit<JobSummary, 'fitScore'>

function seedJobs(now: number): Seed[] {
  const base = (id: number, over: Partial<Seed>): Seed => ({
    id,
    title: `Software Engineering Intern ${id}`,
    company: 'Acme',
    location: 'Boston, MA',
    url: `https://jobs.example/${id}`,
    description: 'Build features with a small team.',
    basicRequirements: ['Currently enrolled in a CS program'],
    preferredRequirements: ['Experience with a web framework'],
    tags: [],
    userStatus: 'new',
    postingStatus: 'active',
    origin: 'crawl',
    sourceId: 1,
    draftId: null,
    postedAt: null,
    firstSeenAt: new Date(now - DAY_MS).toISOString(),
    lastSeenAt: new Date(now).toISOString(),
    ...over,
  })
  return [
    base(1, { title: 'Backend Intern', company: 'Acme', tags: ['java', 'spring'], firstSeenAt: new Date(now - 2 * DAY_MS).toISOString() }),
    base(2, { title: 'Frontend Intern', company: 'Globex', location: 'Remote', tags: ['react', 'typescript'], firstSeenAt: new Date(now - 10 * DAY_MS).toISOString() }),
    base(3, { title: 'Data Intern', company: 'Initech', location: 'New York, NY', tags: ['python'], draftId: 7 }),
    // Posting the extractor has not enriched yet (Epic 6): no requirements, no tags, no location.
    base(4, { title: 'Intern, Unspecified', company: 'Hooli', location: null, description: '', basicRequirements: [], preferredRequirements: [], origin: 'ingest', sourceId: null, firstSeenAt: new Date(now - 40 * DAY_MS).toISOString() }),
  ]
}

export function createDummyJobsApi(now: number = Date.now()): JobsApi {
  const jobs = seedJobs(now)
  const score = (j: Seed) =>
    j.tags.length === 0 ? 0 : Math.round((100 * j.tags.filter((t) => DUMMY_MASTER_TAGS.includes(t)).length) / j.tags.length)
  const view = (j: Seed): JobSummary => ({ ...j, fitScore: score(j) })

  return {
    async list(q: JobQuery) {
      const needle = q.q?.toLowerCase()
      const matches = jobs
        .filter((j) => q.tags.every((t) => j.tags.includes(t)))
        .filter((j) => !q.location || (j.location ?? '').toLowerCase().includes(q.location.toLowerCase()))
        .filter((j) => !q.status || j.userStatus === q.status)
        .filter((j) => !q.maxAgeDays || now - Date.parse(j.firstSeenAt) <= q.maxAgeDays * DAY_MS)
        .filter((j) => !needle || `${j.title} ${j.company} ${j.description}`.toLowerCase().includes(needle))
        .map(view)
        .sort((a, b) =>
          q.sort === 'fit'
            ? b.fitScore - a.fitScore || a.id - b.id
            : Date.parse(b.firstSeenAt) - Date.parse(a.firstSeenAt) || a.id - b.id,
        )
      return { items: matches.slice(q.offset, q.offset + q.limit), total: matches.length, limit: q.limit, offset: q.offset }
    },
    async setStatus(id, status) {
      const job = jobs.find((j) => j.id === id)
      if (!job) throw new Error('not found')
      job.userStatus = status
      return view(job)
    },
  }
}
