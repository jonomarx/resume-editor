import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api as defaultApi } from '../api'
import { ApiError, type Api, type SourceHealth } from '../api/types'
import { SOURCES_LABELS as L } from '../config/constants'

function describe(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 409 && e.code === 'source_exists') return L.exists
    const d = e.details?.[0]?.message
    if (e.status === 400 && d) return `${e.message}: ${d}`
    return e.message
  }
  return L.actionFailed
}

function SourceRow({ h, onChanged, api }: { h: SourceHealth; onChanged(): void; api: Api }) {
  const s = h.source
  const [renaming, setRenaming] = useState(false)
  const [name, setName] = useState(s.name)
  const [confirming, setConfirming] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(fn: () => Promise<unknown>) {
    setError(null)
    try {
      await fn()
      onChanged()
    } catch (e) {
      setError(describe(e))
    }
  }

  return (
    <li aria-label={s.name}>
      {renaming ? (
        <form
          onSubmit={(e) => {
            e.preventDefault()
            void run(async () => {
              await api.sources.update(s.id, { name })
              setRenaming(false)
            })
          }}
        >
          <input aria-label={`New name for ${s.name}`} value={name} onChange={(e) => setName(e.target.value)} required maxLength={100} />
          <button type="submit">{L.save}</button>
          <button type="button" onClick={() => { setRenaming(false); setName(s.name) }}>{L.cancel}</button>
        </form>
      ) : (
        <strong>{s.name}</strong>
      )}{' '}
      <a href={s.url} target="_blank" rel="noreferrer noopener" style={{ overflowWrap: 'anywhere' }}>{s.url}</a>
      <div>
        <span data-testid={`state-${s.id}`}>{L.states[h.state]}</span>
        {h.state === 'failing' && h.lastRun?.error ? ` (${h.lastRun.error})` : ''}
        {' - '}
        {s.lastCrawlAt ? L.lastCrawl(new Date(s.lastCrawlAt).toLocaleString()) : L.neverCrawled}
        {' - '}
        {L.postings(h.activePostings, h.stalePostings)}
      </div>
      {h.lastRun && (
        <div>
          {h.lastRun.status === 'failed' ? L.lastRunFailed(h.lastRun.error) : L.lastRun(h.lastRun.added, h.lastRun.updated, h.lastRun.closed)}
        </div>
      )}
      <label>
        <input type="checkbox" checked={s.enabled} onChange={(e) => void run(() => api.sources.update(s.id, { enabled: e.target.checked }))} aria-label={`${L.enabled}: ${s.name}`} />
        {L.enabled}
      </label>{' '}
      {!renaming && <button type="button" aria-label={`${L.rename} ${s.name}`} onClick={() => setRenaming(true)}>{L.rename}</button>}{' '}
      {confirming ? (
        <>
          <span>{L.removeNote}</span>{' '}
          <button type="button" aria-label={`${L.confirmRemove} ${s.name}`} onClick={() => void run(() => api.sources.remove(s.id))}>{L.confirmRemove}</button>
          <button type="button" onClick={() => setConfirming(false)}>{L.cancel}</button>
        </>
      ) : (
        <button type="button" aria-label={`${L.remove} ${s.name}`} onClick={() => setConfirming(true)}>{L.remove}</button>
      )}
      {error && <p role="alert">{error}</p>}
    </li>
  )
}

export default function SourcesPage({ apiClient = defaultApi }: { apiClient?: Api }) {
  const [items, setItems] = useState<SourceHealth[] | null>(null)
  const [loadError, setLoadError] = useState(false)
  const [url, setUrl] = useState('')
  const [name, setName] = useState('')
  const [addError, setAddError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setItems(await apiClient.sources.health())
      setLoadError(false)
    } catch {
      setLoadError(true)
    }
  }, [apiClient])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load])

  async function add(e: FormEvent) {
    e.preventDefault()
    setAddError(null)
    try {
      await apiClient.sources.create(url.trim(), name.trim() || undefined)
      setUrl('')
      setName('')
      await load()
    } catch (err) {
      setAddError(describe(err))
    }
  }

  return (
    <>
      <h1>{L.heading}</h1>
      <p>{L.intro}</p>
      {loadError && <p role="alert">{L.loadFailed}</p>}
      {items && items.length === 0 && <p>{L.none}</p>}
      <ul>{items?.map((h) => <SourceRow key={h.source.id} h={h} api={apiClient} onChanged={() => void load()} />)}</ul>
      <form onSubmit={add}>
        <label>
          {L.urlLabel}
          <input type="url" value={url} onChange={(e) => setUrl(e.target.value)} required maxLength={2000} />
        </label>
        <label>
          {L.nameLabel}
          <input value={name} onChange={(e) => setName(e.target.value)} maxLength={100} />
        </label>
        <button type="submit">{L.add}</button>
        {addError && <p role="alert">{addError}</p>}
      </form>
    </>
  )
}
