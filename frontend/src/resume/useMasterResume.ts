import { useCallback, useEffect, useRef, useState } from 'react'
import { ApiError, type Api, type ResumeTree, type ResumeVersionSummary } from '../api/types'
import { AUTOSAVE_DEBOUNCE_MS } from '../config/constants'

export type SaveStatus = 'loading' | 'saved' | 'dirty' | 'saving' | 'conflict' | 'error'

function describeError(e: unknown): string {
  if (e instanceof ApiError && e.details?.length) return `${e.message}: ${e.details.map((d) => d.message).join('; ')}`
  return e instanceof Error ? e.message : String(e)
}

/**
 * Loads the master resume, autosaves edits (debounced) with optimistic locking, and manages history.
 * Saves are serialized: at most one request is in flight, and edits made meanwhile trigger one more save
 * afterwards, based on the version the previous save returned.
 */
export function useMasterResume(api: Api) {
  const [tree, setTreeState] = useState<ResumeTree | null>(null)
  const [version, setVersion] = useState(0)
  const [status, setStatus] = useState<SaveStatus>('loading')
  const [errorMessage, setErrorMessage] = useState<string | null>(null)
  const [versions, setVersions] = useState<ResumeVersionSummary[] | null>(null)

  const versionRef = useRef(0)
  const treeRef = useRef<ResumeTree | null>(null)
  const dirty = useRef(false)
  const saving = useRef(false)
  const stopped = useRef(false) // conflict: no autosave until the user reloads
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)

  const clearTimer = () => {
    if (timer.current) clearTimeout(timer.current)
    timer.current = null
  }

  const adopt = useCallback((doc: { version: number; tree: ResumeTree }) => {
    versionRef.current = doc.version
    treeRef.current = doc.tree
    dirty.current = false
    stopped.current = false
    setVersion(doc.version)
    setTreeState(doc.tree)
    setErrorMessage(null)
    setStatus('saved')
  }, [])

  const load = useCallback(async () => {
    clearTimer()
    setStatus('loading')
    try {
      adopt(await api.resume.getMaster())
    } catch (e) {
      setErrorMessage(describeError(e))
      setStatus('error')
    }
  }, [api, adopt])

  useEffect(() => {
    // eslint-disable-next-line react-hooks/set-state-in-effect
    void load()
    return clearTimer
  }, [load])

  const flush = useCallback(async () => {
    clearTimer()
    if (saving.current || stopped.current) return
    saving.current = true
    try {
      // Loop so that edits made while a save is in flight are saved next, on top of the returned version.
      while (dirty.current && treeRef.current) {
        dirty.current = false
        setStatus('saving')
        try {
          const doc = await api.resume.saveMaster(versionRef.current, treeRef.current)
          versionRef.current = doc.version
          setVersion(doc.version)
        } catch (e) {
          dirty.current = true
          if (e instanceof ApiError && e.status === 409) {
            stopped.current = true
            setStatus('conflict')
          } else {
            setErrorMessage(describeError(e))
            setStatus('error')
          }
          return
        }
      }
      setStatus('saved')
    } finally {
      saving.current = false
    }
  }, [api])

  const setTree = useCallback(
    (t: ResumeTree) => {
      treeRef.current = t
      dirty.current = true
      setTreeState(t)
      if (stopped.current) return
      setStatus((s) => (s === 'saving' ? s : 'dirty'))
      clearTimer()
      timer.current = setTimeout(() => void flush(), AUTOSAVE_DEBOUNCE_MS)
    },
    [flush],
  )

  const loadVersions = useCallback(async () => {
    setVersions(await api.resume.listVersions())
  }, [api])

  const restore = useCallback(
    async (v: number) => {
      if (saving.current || dirty.current) return
      try {
        adopt(await api.resume.restoreVersion(v, versionRef.current))
        await loadVersions()
      } catch (e) {
        if (e instanceof ApiError && e.status === 409) {
          stopped.current = true
          setStatus('conflict')
        } else {
          setErrorMessage(describeError(e))
          setStatus('error')
        }
      }
    },
    [api, adopt, loadVersions],
  )

  return { tree, version, status, errorMessage, setTree, reload: load, retry: flush, versions, loadVersions, restore }
}
