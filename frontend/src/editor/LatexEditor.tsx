import { basicSetup } from 'codemirror'
import { EditorState } from '@codemirror/state'
import { EditorView } from '@codemirror/view'
import { useEffect, useRef } from 'react'
import { EDITOR_LABELS, LATEX_SNIPPETS } from '../config/constants'
import { expandSnippet } from './snippets'

interface Props {
  value: string
  onChange(value: string): void
}

/** CodeMirror 6 editor for one node's own LaTeX. Mount it with `key={node.id}` so each node gets a fresh state. */
export default function LatexEditor({ value, onChange }: Props) {
  const host = useRef<HTMLDivElement>(null)
  const view = useRef<EditorView | null>(null)
  const onChangeRef = useRef(onChange)

  useEffect(() => {
    onChangeRef.current = onChange
  })

  useEffect(() => {
    const v = new EditorView({
      parent: host.current!,
      state: EditorState.create({
        doc: value,
        extensions: [
          basicSetup,
          EditorView.contentAttributes.of({ 'aria-label': EDITOR_LABELS.editorAria }),
          EditorView.updateListener.of((u) => {
            if (u.docChanged) onChangeRef.current(u.state.doc.toString())
          }),
        ],
      }),
    })
    view.current = v
    return () => {
      v.destroy()
      view.current = null
    }
    // The document is only seeded here; later external changes are synced by the effect below.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Sync external changes (for example a restore) without echoing our own edits back.
  useEffect(() => {
    const v = view.current
    if (v && v.state.doc.toString() !== value) {
      v.dispatch({ changes: { from: 0, to: v.state.doc.length, insert: value } })
    }
  }, [value])

  function insertSnippet(text: string) {
    const v = view.current
    if (!v) return
    const { insert, cursor } = expandSnippet(text)
    const { from, to } = v.state.selection.main
    v.dispatch({ changes: { from, to, insert }, selection: { anchor: from + cursor }, scrollIntoView: true })
    v.focus()
  }

  return (
    <div>
      <div role="toolbar" aria-label="Snippets">
        {LATEX_SNIPPETS.map((s) => (
          <button key={s.label} type="button" onClick={() => insertSnippet(s.text)}>
            {s.label}
          </button>
        ))}
      </div>
      <div ref={host} />
    </div>
  )
}
