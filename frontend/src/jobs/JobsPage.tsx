import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { api as defaultApi } from '../api'
import type { Api, JobQuery, JobSummary, JobUserStatus, Tag } from '../api/types'
import { loadFeedPage, type FeedPage } from './loadFeed'
import { JOB_FEED, JOB_FEED_LABELS as L } from '../config/constants'

const STATUSES: JobUserStatus[] = ['new', 'saved', 'dismissed', 'applied']

function JobCard({ job, tagNames, onStatus, error }: { job: JobSummary; tagNames: Map<string, string>; onStatus(id: number, s: JobUserStatus): void; error: boolean }) {
  const toggle = (target: JobUserStatus, current: JobUserStatus) => onStatus(job.id, current === target ? 'new' : target)
  return (
    <li aria-label={`${job.title} at ${job.company}`}>
      <h2>{job.title}</h2>
      <div>
        {job.company} - {job.location ?? L.noLocation} - <strong>{L.fit(job.fitScore)}</strong> - {L.statuses[job.userStatus]}
      </div>
      <p>{job.description || L.noDescription}</p>
      {([['basic', job.basicRequirements], ['preferred', job.preferredRequirements]] as const).map(([key, items]) => (
        <section key={key} aria-label={L[key]}>
          <h3>{L[key]}</h3>
          {items.length === 0 ? <p>{L.noRequirements}</p> : <ul>{items.map((r, i) => <li key={i}>{r}</li>)}</ul>}
        </section>
      ))}
      <div aria-label="Tags">
        {job.tags.length === 0 ? L.noTags : job.tags.map((t) => <span key={t}>[{tagNames.get(t) ?? t}] </span>)}
      </div>
      <button type="button" aria-pressed={job.userStatus === 'saved'} aria-label={`${job.userStatus === 'saved' ? L.unsave : L.save} ${job.title}`} onClick={() => toggle('saved', job.userStatus)}>
        {job.userStatus === 'saved' ? L.unsave : L.save}
      </button>{' '}
      <button type="button" aria-pressed={job.userStatus === 'dismissed'} aria-label={`${job.userStatus === 'dismissed' ? L.restore : L.dismiss} ${job.title}`} onClick={() => toggle('dismissed', job.userStatus)}>
        {job.userStatus === 'dismissed' ? L.restore : L.dismiss}
      </button>{' '}
      <a href={job.url} target="_blank" rel="noreferrer noopener">{L.openPosting}</a>
      {job.draftId !== null && <> <Link to={`/drafts/${job.draftId}`}>{L.openDraft}</Link></>}
      {error && <p role="alert">{L.actionFailed}</p>}
    </li>
  )
}

type StatusChoice = JobUserStatus | 'any' | ''

/** Filters, sort and page live in the URL query; defaults are left out of it. "" means the default: hide dismissed. */
function readParams(sp: URLSearchParams) {
  const status = sp.get('status')
  const age = Number(sp.get('maxAgeDays'))
  const offset = Number(sp.get('offset'))
  return {
    sort: (sp.get('sort') === 'newest' ? 'newest' : 'fit') as JobQuery['sort'],
    tags: sp.getAll('tag'),
    status: (status === 'any' || STATUSES.includes(status as JobUserStatus) ? status : '') as StatusChoice,
    maxAgeDays: (JOB_FEED.freshnessDays as readonly number[]).includes(age) ? age : 0,
    location: sp.get('location') ?? '',
    q: sp.get('q') ?? '',
    offset: Number.isInteger(offset) && offset > 0 ? offset : 0,
  }
}

function writeParams(f: ReturnType<typeof readParams>): URLSearchParams {
  const sp = new URLSearchParams()
  if (f.sort !== 'fit') sp.set('sort', f.sort)
  for (const t of f.tags) sp.append('tag', t)
  if (f.status) sp.set('status', f.status)
  if (f.maxAgeDays) sp.set('maxAgeDays', String(f.maxAgeDays))
  if (f.location) sp.set('location', f.location)
  if (f.q) sp.set('q', f.q)
  if (f.offset) sp.set('offset', String(f.offset))
  return sp
}

export default function JobsPage({ apiClient = defaultApi }: { apiClient?: Api }) {
  const [searchParams, setSearchParams] = useSearchParams()
  const filters = readParams(searchParams)
  const { sort, tags, status, maxAgeDays, offset } = filters
  // Offsets of the pages before this one; kept in history state because "previous" cannot be computed when dismissed postings are hidden.
  const prevOffsets = ((useLocation().state as { prev?: number[] } | null)?.prev ?? []).filter((n) => Number.isInteger(n))
  const [vocabulary, setVocabulary] = useState<Tag[]>([])
  // Text filters apply on submit, the rest immediately; the boxes start from the URL.
  const [location, setLocation] = useState(filters.location)
  const [search, setSearch] = useState(filters.q)
  const [page, setPage] = useState<FeedPage | null>(null)
  const [reload, setReload] = useState(0)
  const [loadError, setLoadError] = useState(false)
  const [failedId, setFailedId] = useState<number | null>(null)

  useEffect(() => {
    let cancelled = false
    apiClient.tags.listTags().then((t) => !cancelled && setVocabulary(t)).catch(() => undefined) // slugs still work as filters labels
    return () => {
      cancelled = true
    }
  }, [apiClient])
  const tagNames = useMemo(() => new Map(vocabulary.map((t) => [t.slug, t.name])), [vocabulary])

  const hideDismissed = status === ''
  const tagKey = tags.join('\n')
  useEffect(() => {
    let cancelled = false
    const query: JobQuery = {
      sort,
      tags: tagKey ? tagKey.split('\n') : [],
      location: filters.location || undefined,
      q: filters.q || undefined,
      status: status && status !== 'any' ? status : undefined,
      maxAgeDays: maxAgeDays || undefined,
      limit: JOB_FEED.pageSize,
      offset,
    }
    loadFeedPage(apiClient.jobs, query, hideDismissed)
      .then((p) => {
        if (cancelled) return
        setPage(p)
        setLoadError(false)
      })
      .catch(() => !cancelled && setLoadError(true))
    return () => {
      cancelled = true
    }
  }, [apiClient, sort, tagKey, filters.location, filters.q, status, maxAgeDays, offset, hideDismissed, reload])

  /** A filter change returns to the first page. */
  const setFilters = (change: Partial<ReturnType<typeof readParams>>) => setSearchParams(writeParams({ ...filters, offset: 0, ...change }))

  async function changeStatus(id: number, next: JobUserStatus) {
    setFailedId(null)
    try {
      const updated = await apiClient.jobs.setStatus(id, next)
      const stays = status === '' ? updated.userStatus !== 'dismissed' : status === 'any' || updated.userStatus === status
      if (stays) {
        setPage((p) => p && { ...p, items: p.items.map((j) => (j.id === id ? updated : j)) })
      } else {
        // The card leaves this list: drop it now and refetch so the page fills up again.
        setPage((p) => p && { ...p, items: p.items.filter((j) => j.id !== id) })
        setReload((n) => n + 1)
      }
    } catch {
      setFailedId(id)
    }
  }

  function submitSearch(e: FormEvent) {
    e.preventDefault()
    setFilters({ location: location.trim(), q: search.trim() })
  }

  const goTo = (target: number, prev: number[]) => setSearchParams(writeParams({ ...filters, offset: target }), { state: { prev } })
  const from = page && page.items.length ? offset + 1 : 0
  const to = page ? offset + page.items.length : 0

  return (
    <>
      <h1>{L.heading}</h1>
      <form onSubmit={submitSearch} aria-label="Filters">
        <label>
          {L.sortLabel}
          <select value={sort} onChange={(e) => setFilters({ sort: e.target.value as JobQuery['sort'] })}>
            <option value="fit">{L.sorts.fit}</option>
            <option value="newest">{L.sorts.newest}</option>
          </select>
        </label>
        <label>
          {L.statusLabel}
          <select value={status} onChange={(e) => setFilters({ status: e.target.value as StatusChoice })}>
            <option value="">{L.defaultStatus}</option>
            <option value="any">{L.anyStatus}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{L.statuses[s]}</option>)}
          </select>
        </label>
        <label>
          {L.freshnessLabel}
          <select value={maxAgeDays} onChange={(e) => setFilters({ maxAgeDays: Number(e.target.value) })}>
            {JOB_FEED.freshnessDays.map((d) => <option key={d} value={d}>{d === 0 ? L.anyAge : L.days(d)}</option>)}
          </select>
        </label>
        <label>
          {L.locationLabel}
          <input value={location} onChange={(e) => setLocation(e.target.value)} maxLength={100} />
        </label>
        <label>
          {L.searchLabel}
          <input value={search} onChange={(e) => setSearch(e.target.value)} maxLength={200} />
        </label>
        <button type="submit">{L.applySearch}</button>
        <fieldset>
          <legend>{L.tagsLegend}</legend>
          {vocabulary.length === 0 && <span>{L.noVocabulary}</span>}
          {vocabulary.map((t) => (
            <label key={t.slug}>
              <input
                type="checkbox"
                checked={tags.includes(t.slug)}
                onChange={(e) => setFilters({ tags: e.target.checked ? [...tags, t.slug] : tags.filter((s) => s !== t.slug) })}
              />
              {t.name}
            </label>
          ))}
        </fieldset>
      </form>
      {loadError && <p role="alert">{L.loadFailed}</p>}
      {page && page.items.length === 0 && !loadError && <p>{L.none}</p>}
      <ul>
        {page?.items.map((j) => <JobCard key={j.id} job={j} tagNames={tagNames} onStatus={(id, s) => void changeStatus(id, s)} error={failedId === j.id} />)}
      </ul>
      {page && (page.items.length > 0 || offset > 0) && (
        <nav aria-label="Pages">
          <button type="button" disabled={offset === 0} onClick={() => goTo(prevOffsets.length ? prevOffsets[prevOffsets.length - 1] : 0, prevOffsets.slice(0, -1))}>{L.previous}</button>{' '}
          <span>{hideDismissed ? L.shown(page.items.length) : L.range(from, to, page.total)}</span>{' '}
          <button type="button" disabled={!page.hasMore} onClick={() => goTo(page.next, [...prevOffsets, offset])}>{L.next}</button>
        </nav>
      )}
    </>
  )
}
