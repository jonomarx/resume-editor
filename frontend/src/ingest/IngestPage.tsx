import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { api as defaultApi } from '../api'
import { ApiError, type Api, type IngestEvent, type IngestKind, type IngestToken } from '../api/types'
import { INGEST_KINDS, INGEST_LABELS as L, INGEST_POLL_MAX_ATTEMPTS, INGEST_POLL_MS } from '../config/constants'
import { buildBookmarkletCode, bookmarkletUrl } from './bookmarklet'

type PasteState =
  | { phase: 'idle' }
  | { phase: 'sending' }
  | { phase: 'tracking'; eventId: number; duplicate: boolean; event: IngestEvent | null; gaveUp: boolean }
  | { phase: 'error'; message: string }

function sendError(e: unknown): string {
  if (e instanceof ApiError) {
    if (e.status === 413) return L.tooLarge
    if (e.status === 429) return L.tooMany
    const d = e.details?.[0]?.message
    if (e.status === 400 && d) return `${e.message}: ${d}`
    return e.message
  }
  return L.sendFailed
}

function PasteBox({ api }: { api: Api }) {
  const [kind, setKind] = useState<IngestKind>(INGEST_KINDS[0].value)
  const [payload, setPayload] = useState('')
  const [hint, setHint] = useState('')
  const [state, setState] = useState<PasteState>({ phase: 'idle' })
  const run = useRef(0) // a newer submit or unmount invalidates the polling loop of an older one

  useEffect(
    () => () => {
      run.current++
    },
    [],
  )

  async function track(eventId: number, duplicate: boolean, mine: number) {
    for (let attempt = 0; attempt < INGEST_POLL_MAX_ATTEMPTS; attempt++) {
      await new Promise((r) => setTimeout(r, INGEST_POLL_MS))
      if (run.current !== mine) return
      try {
        const event = await api.ingest.getEvent(eventId)
        if (run.current !== mine) return
        const final = event.status === 'done' || event.status === 'failed'
        const gaveUp = !final && attempt + 1 >= INGEST_POLL_MAX_ATTEMPTS
        setState({ phase: 'tracking', eventId, duplicate, event, gaveUp })
        if (final) return
      } catch (e) {
        if (run.current === mine) setState({ phase: 'error', message: sendError(e) })
        return
      }
    }
  }

  async function submit(e: FormEvent) {
    e.preventDefault()
    const mine = ++run.current
    setState({ phase: 'sending' })
    try {
      const accepted = await api.ingest.ingest(kind, payload, hint.trim() || undefined)
      if (run.current !== mine) return
      setState({ phase: 'tracking', eventId: accepted.ingestEventId, duplicate: accepted.duplicate, event: null, gaveUp: false })
      void track(accepted.ingestEventId, accepted.duplicate, mine)
    } catch (err) {
      if (run.current === mine) setState({ phase: 'error', message: sendError(err) })
    }
  }

  return (
    <section aria-label={L.pasteHeading}>
      <h2>{L.pasteHeading}</h2>
      <form onSubmit={submit}>
        <label>
          {L.kindLabel}
          <select value={kind} onChange={(e) => setKind(e.target.value as IngestKind)}>
            {INGEST_KINDS.map((k) => (
              <option key={k.value} value={k.value}>{k.label}</option>
            ))}
          </select>
        </label>
        <label>
          {L.payloadLabel}
          <textarea value={payload} onChange={(e) => setPayload(e.target.value)} required rows={8} />
        </label>
        <label>
          {L.hintLabel}
          <input value={hint} onChange={(e) => setHint(e.target.value)} maxLength={500} />
        </label>
        <button type="submit" disabled={state.phase === 'sending' || !payload.trim()}>
          {state.phase === 'sending' ? L.sending : L.submit}
        </button>
      </form>
      <div role="status">
        {state.phase === 'tracking' && (
          <>
            {state.duplicate && <p>{L.duplicate}</p>}
            {state.event?.status === 'done' && <p>{L.done}</p>}
            {state.event?.status === 'failed' && <p>{L.failed(state.event.error?.message ?? '')}</p>}
            {state.gaveUp && <p>{L.gaveUp}</p>}
            {(!state.event || state.event.status === 'queued' || state.event.status === 'processing') && !state.gaveUp && <p>{L.queued}</p>}
          </>
        )}
      </div>
      {state.phase === 'error' && <p role="alert">{state.message}</p>}
    </section>
  )
}

function BookmarkletSection({ api }: { api: Api }) {
  const [tokens, setTokens] = useState<IngestToken[] | null>(null)
  const [label, setLabel] = useState<string>(L.tokenDefaultLabel)
  // The secret lives only in this component's state: never written to web storage, gone on reload.
  const [secret, setSecret] = useState<string | null>(null)
  const [copied, setCopied] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(async () => {
    try {
      setTokens(await api.ingest.listTokens())
    } catch {
      setError(L.tokenError)
    }
  }, [api])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
  }, [load])

  async function create(e: FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      const created = await api.ingest.createToken(label.trim())
      setSecret(created.token)
      setCopied(false)
      await load()
    } catch {
      setError(L.tokenError)
    }
  }

  async function revoke(id: number) {
    setError(null)
    try {
      await api.ingest.revokeToken(id)
      await load()
    } catch {
      setError(L.tokenError)
    }
  }

  const code = secret ? buildBookmarkletCode(window.location.origin, secret) : null

  return (
    <section aria-label={L.bookmarkletHeading}>
      <h2>{L.bookmarkletHeading}</h2>
      <p>{L.bookmarkletIntro}</p>
      <form onSubmit={create}>
        <label>
          {L.tokenLabelLabel}
          <input value={label} onChange={(e) => setLabel(e.target.value)} required maxLength={60} />
        </label>
        <button type="submit" disabled={!label.trim()}>{L.createToken}</button>
      </form>
      {error && <p role="alert">{error}</p>}
      {code && (
        <div>
          <p>{L.tokenSecretNote}</p>
          {/* React blocks javascript: URLs in href props, so the draggable link gets its href from a ref. */}
          <a ref={(el) => el?.setAttribute('href', bookmarkletUrl(code))} onClick={(e) => e.preventDefault()}>{L.bookmarkletLink}</a>{' '}
          <button
            type="button"
            onClick={() => {
              void navigator.clipboard?.writeText('javascript:' + code).then(() => setCopied(true))
            }}
          >
            {L.copy}
          </button>
          {copied && <span role="status"> {L.copied}</span>}
          <textarea readOnly aria-label={L.codeLabel} value={'javascript:' + code} rows={4} style={{ width: '100%' }} />
        </div>
      )}
      <h3>{L.tokensHeading}</h3>
      {tokens && tokens.length === 0 && <p>{L.noTokens}</p>}
      <ul>
        {tokens?.map((t) => (
          <li key={t.id} aria-label={t.label}>
            {t.label} ({t.revokedAt ? L.revoked : L.lastUsed(t.lastUsedAt)}){' '}
            {!t.revokedAt && <button type="button" aria-label={`${L.revoke} ${t.label}`} onClick={() => void revoke(t.id)}>{L.revoke}</button>}
          </li>
        ))}
      </ul>
    </section>
  )
}

export default function IngestPage({ apiClient = defaultApi }: { apiClient?: Api }) {
  return (
    <>
      <h1>{L.heading}</h1>
      <PasteBox api={apiClient} />
      <BookmarkletSection api={apiClient} />
    </>
  )
}
