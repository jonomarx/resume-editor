import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { vi } from 'vitest'
import JobsPage from './JobsPage'
import { createDummyJobsApi } from '../dummy/jobs'
import { createFakeApi } from '../test/fakeServer'
import { createHttpApi } from '../api/http'
import { JOB_FEED } from '../config/constants'
import type { Api, JobsApi, Tag } from '../api/types'

const tag = (id: number, name: string, slug: string): Tag => ({ id, name, slug, category: 'language', aliases: [] })
const vocabulary = [tag(1, 'Java', 'java'), tag(2, 'React', 'react'), tag(3, 'Python', 'python')]

function setup(jobs: JobsApi = createDummyJobsApi()) {
  const api: Api = { ...createFakeApi(), jobs, tags: { listTags: async () => vocabulary, createTag: async () => vocabulary[0] } }
  render(<MemoryRouter><JobsPage apiClient={api} /></MemoryRouter>)
  return jobs
}
const titles = () => screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
const card = (name: RegExp | string) => screen.findByRole('listitem', { name })

describe('JobsPage against the dummy jobs API', () => {
  it('shows cards with fit, requirements, description and tags, best fit first', async () => {
    setup()
    const backend = await card(/Backend Intern/)
    expect(within(backend).getByText('Fit 50')).toBeInTheDocument()
    expect(backend).toHaveTextContent('Boston, MA')
    expect(backend).toHaveTextContent('Build features with a small team.')
    expect(backend).toHaveTextContent('Currently enrolled in a CS program')
    expect(backend).toHaveTextContent('Experience with a web framework')
    expect(backend).toHaveTextContent('[Java]')
    expect(backend).toHaveTextContent('[spring]') // not in the vocabulary: falls back to the slug
    expect(titles()).toEqual(['Data Intern', 'Backend Intern', 'Frontend Intern', 'Intern, Unspecified'])
  })

  it('shows empty states for a posting without requirements, tags or location', async () => {
    setup()
    const bare = await card(/Intern, Unspecified/)
    expect(bare).toHaveTextContent('Location not listed')
    expect(bare).toHaveTextContent('No description.')
    expect(bare).toHaveTextContent('No tags')
    expect(within(bare).getAllByText('None listed')).toHaveLength(2)
  })

  it('filters by several tags, which must all match', async () => {
    setup()
    await card(/Backend Intern/)
    await userEvent.click(screen.getByLabelText('Java'))
    expect(titles()).toEqual(['Backend Intern'])
    await userEvent.click(screen.getByLabelText('React'))
    expect(await screen.findByText('No postings match these filters.')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Java'))
    expect(await screen.findByText('Frontend Intern')).toBeInTheDocument()
  })

  it('filters by status, freshness and location/search, and sorts by newest', async () => {
    setup()
    await card(/Backend Intern/)
    await userEvent.selectOptions(screen.getByLabelText('Posted within'), '7')
    expect(titles()).not.toContain('Frontend Intern')
    expect(titles()).not.toContain('Intern, Unspecified')
    await userEvent.selectOptions(screen.getByLabelText('Posted within'), '0')
    await userEvent.type(screen.getByLabelText('Location'), 'remote')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    await screen.findByText('Frontend Intern')
    expect(titles()).toEqual(['Frontend Intern'])
    await userEvent.clear(screen.getByLabelText('Location'))
    await userEvent.type(screen.getByLabelText('Search'), 'initech')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    await screen.findByText('Data Intern')
    expect(titles()).toEqual(['Data Intern'])
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'saved')
    expect(await screen.findByText('No postings match these filters.')).toBeInTheDocument()
  })

  it('sorts by newest', async () => {
    setup()
    await card(/Backend Intern/)
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'newest')
    await screen.findByText('Data Intern')
    expect(titles()).toEqual(['Data Intern', 'Backend Intern', 'Frontend Intern', 'Intern, Unspecified'])
  })

  it('saves, unsaves and dismisses a posting', async () => {
    setup()
    const backend = await card(/Backend Intern/)
    await userEvent.click(within(backend).getByRole('button', { name: 'Save Backend Intern' }))
    expect(await within(backend).findByRole('button', { name: 'Unsave Backend Intern' })).toHaveAttribute('aria-pressed', 'true')
    expect(backend).toHaveTextContent('Saved')
    await userEvent.click(within(backend).getByRole('button', { name: 'Unsave Backend Intern' }))
    expect(await within(backend).findByRole('button', { name: 'Save Backend Intern' })).toBeInTheDocument()
    await userEvent.click(within(backend).getByRole('button', { name: 'Dismiss Backend Intern' }))
    expect(await within(backend).findByRole('button', { name: 'Restore Backend Intern' })).toBeInTheDocument()
  })

  it('drops a card that no longer matches the active status filter', async () => {
    const jobs = createDummyJobsApi()
    await jobs.setStatus(1, 'saved')
    setup(jobs)
    await card(/Backend Intern/)
    await userEvent.selectOptions(screen.getByLabelText('Status'), 'saved')
    await screen.findByRole('button', { name: 'Unsave Backend Intern' })
    expect(titles()).toEqual(['Backend Intern'])
    await userEvent.click(screen.getByRole('button', { name: 'Unsave Backend Intern' }))
    expect(await screen.findByText('No postings match these filters.')).toBeInTheDocument()
  })

  it('shows an error when saving fails, and when the feed cannot load', async () => {
    const jobs = createDummyJobsApi()
    jobs.setStatus = vi.fn().mockRejectedValue(new Error('boom'))
    setup(jobs)
    const backend = await card(/Backend Intern/)
    await userEvent.click(within(backend).getByRole('button', { name: 'Save Backend Intern' }))
    expect(await within(backend).findByRole('alert')).toHaveTextContent('Could not update that posting')
  })

  it('shows a load error', async () => {
    const jobs = createDummyJobsApi()
    jobs.list = vi.fn().mockRejectedValue(new Error('boom'))
    setup(jobs)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load the job feed.')
  })

  it('links to an existing draft only', async () => {
    setup()
    const data = await card(/Data Intern/)
    expect(within(data).getByRole('link', { name: 'Open draft' })).toHaveAttribute('href', '/drafts/7')
    expect(within(await card(/Backend Intern/)).queryByRole('link', { name: 'Open draft' })).not.toBeInTheDocument()
  })

  it('pages through results and returns to the first page when a filter changes', async () => {
    const calls: number[] = []
    const base = createDummyJobsApi()
    const jobs: JobsApi = {
      ...base,
      list: async (q) => {
        calls.push(q.offset)
        const template = (await base.list({ ...q, offset: 0, limit: 1 })).items[0]
        const items = Array.from({ length: JOB_FEED.pageSize }, (_, i) => ({ ...template, id: q.offset + i + 1, title: `Job ${q.offset + i + 1}` }))
        return { items, total: JOB_FEED.pageSize * 2, limit: q.limit, offset: q.offset }
      },
    }
    setup(jobs)
    await screen.findByText('Job 1')
    expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Next' }))
    await screen.findByText('Job 51')
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled()
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'newest')
    await screen.findByText('Job 1')
    expect(calls).toEqual([0, 50, 0])
  })
})

describe('real client paths for the jobs API', () => {
  it('sends repeated tag parameters, omits empty filters, and PUTs the status', async () => {
    const seen: { url: string; method: string; body?: string }[] = []
    const fetchImpl = (async (url: string, init: RequestInit = {}) => {
      seen.push({ url, method: init.method ?? 'GET', body: init.body as string | undefined })
      if (url === '/api/me') return new Response(null, { status: 200 })
      return new Response(JSON.stringify({ items: [], total: 0, limit: 50, offset: 0 }), { status: 200 })
    }) as unknown as typeof fetch
    const api = createHttpApi(fetchImpl)
    await api.jobs.list({ sort: 'fit', tags: ['java', 'react'], location: 'Boston', maxAgeDays: 7, limit: 50, offset: 0 })
    expect(seen[0].url).toBe('/api/jobs?sort=fit&limit=50&offset=0&tag=java&tag=react&location=Boston&maxAgeDays=7')
    await api.jobs.setStatus(5, 'dismissed')
    const put = seen.find((s) => s.method === 'PUT')!
    expect(put.url).toBe('/api/jobs/5/status')
    expect(JSON.parse(put.body!)).toEqual({ status: 'dismissed' })
  })
})
