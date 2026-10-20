import { useCallback, useEffect, useRef, useState } from 'react'
import type { Api, RenderResult, ResumeTree } from '../api/types'
import { RENDER_DEBOUNCE_MS } from '../config/constants'

export interface RenderState {
  rendering: boolean
  result: RenderResult | null
  /** Transport or server failure (429, 503, ...), as opposed to a LaTeX compile failure in `result`. */
  error: string | null
}

/**
 * Renders `tree` once it first appears, then RENDER_DEBOUNCE_MS after the last change; renderNow skips the wait.
 * Only the newest request's answer is kept.
 */
export function useRender(api: Api, tree: ResumeTree | null) {
  const [state, setState] = useState<RenderState>({ rendering: false, result: null, error: null })
  const latest = useRef<ResumeTree | null>(tree)
  const seq = useRef(0)
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const first = useRef(true)

  const run = useCallback(async () => {
    const t = latest.current
    if (!t) return
    const mine = ++seq.current
    setState((s) => ({ ...s, rendering: true }))
    try {
      const result = await api.render.render(t)
      if (mine === seq.current) setState({ rendering: false, result, error: null })
    } catch (e) {
      if (mine === seq.current) setState((s) => ({ ...s, rendering: false, error: e instanceof Error ? e.message : String(e) }))
    }
  }, [api])

  useEffect(() => {
    latest.current = tree
    if (!tree) return
    if (timer.current) clearTimeout(timer.current)
    timer.current = setTimeout(() => void run(), first.current ? 0 : RENDER_DEBOUNCE_MS)
    first.current = false
    return () => {
      if (timer.current) clearTimeout(timer.current)
    }
  }, [tree, run])

  const renderNow = useCallback(() => {
    if (timer.current) clearTimeout(timer.current)
    return run()
  }, [run])

  return { ...state, renderNow }
}
