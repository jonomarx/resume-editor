import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api as defaultApi } from '../api'
import type { Api, JobPage, JobQuery, JobSummary, JobUserStatus, Tag } from '../api/types'
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

export default function JobsPage({ apiClient = defaultApi }: { apiClient?: Api }) {
  const [vocabulary, setVocabulary] = useState<Tag[]>([])
  const [sort, setSort] = useState<JobQuery['sort']>('fit')
  const [tags, setTags] = useState<string[]>([])
  const [status, setStatus] = useState<JobUserStatus | ''>('')
  const [maxAgeDays, setMaxAgeDays] = useState(0)
  const [location, setLocation] = useState('')
  const [search, setSearch] = useState('')
  // Text filters apply on submit, the rest immediately.
  const [applied, setApplied] = useState({ location: '', q: '' })
  const [offset, setOffset] = useState(0)
  const [page, setPage] = useState<JobPage | null>(null)
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

  const query = useMemo<JobQuery>(
    () => ({
      sort,
      tags,
      location: applied.location || undefined,
      q: applied.q || undefined,
      status: status || undefined,
      maxAgeDays: maxAgeDays || undefined,
      limit: JOB_FEED.pageSize,
      offset,
    }),
    [sort, tags, applied, status, maxAgeDays, offset],
  )

  useEffect(() => {
    let cancelled = false
    apiClient.jobs
      .list(query)
      .then((p) => {
        if (cancelled) return
        setPage(p)
        setLoadError(false)
      })
      .catch(() => !cancelled && setLoadError(true))
    return () => {
      cancelled = true
    }
  }, [apiClient, query])

  /** Any filter change returns to the first page. */
  const filter = useCallback(<T,>(set: (v: T) => void) => (v: T) => {
    set(v)
    setOffset(0)
  }, [])

  async function changeStatus(id: number, next: JobUserStatus) {
    setFailedId(null)
    try {
      const updated = await apiClient.jobs.setStatus(id, next)
      setPage((p) => {
        if (!p) return p
        const stays = !status || updated.userStatus === status
        return {
          ...p,
          total: stays ? p.total : p.total - 1,
          items: stays ? p.items.map((j) => (j.id === id ? updated : j)) : p.items.filter((j) => j.id !== id),
        }
      })
    } catch {
      setFailedId(id)
    }
  }

  function submitSearch(e: FormEvent) {
    e.preventDefault()
    setApplied({ location: location.trim(), q: search.trim() })
    setOffset(0)
  }

  const from = page && page.items.length ? page.offset + 1 : 0
  const to = page ? page.offset + page.items.length : 0

  return (
    <>
      <h1>{L.heading}</h1>
      <form onSubmit={submitSearch} aria-label="Filters">
        <label>
          {L.sortLabel}
          <select value={sort} onChange={(e) => filter(setSort)(e.target.value as JobQuery['sort'])}>
            <option value="fit">{L.sorts.fit}</option>
            <option value="newest">{L.sorts.newest}</option>
          </select>
        </label>
        <label>
          {L.statusLabel}
          <select value={status} onChange={(e) => filter(setStatus)(e.target.value as JobUserStatus | '')}>
            <option value="">{L.anyStatus}</option>
            {STATUSES.map((s) => <option key={s} value={s}>{L.statuses[s]}</option>)}
          </select>
        </label>
        <label>
          {L.freshnessLabel}
          <select value={maxAgeDays} onChange={(e) => filter(setMaxAgeDays)(Number(e.target.value))}>
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
                onChange={(e) => filter(setTags)(e.target.checked ? [...tags, t.slug] : tags.filter((s) => s !== t.slug))}
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
      {page && page.total > 0 && (
        <nav aria-label="Pages">
          <button type="button" disabled={offset === 0} onClick={() => setOffset(Math.max(0, offset - JOB_FEED.pageSize))}>{L.previous}</button>{' '}
          <span>{L.range(from, to, page.total)}</span>{' '}
          <button type="button" disabled={to >= page.total} onClick={() => setOffset(offset + JOB_FEED.pageSize)}>{L.next}</button>
        </nav>
      )}
    </>
  )
}
