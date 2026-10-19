import { useState, type FormEvent } from 'react'
import { OUTLINE_LABELS } from '../config/constants'
import { addNode, allowedChildTypes, canMove, deleteNode, isFixed, moveNode, renameNode, type IdFactory } from './treeOps'
import type { ResumeNode, ResumeTree } from './types'

interface Props {
  tree: ResumeTree
  onChange(tree: ResumeTree): void
  selectedId?: string | null
  onSelect?(id: string): void
  /** Injectable for tests; defaults to crypto.randomUUID. */
  newId?: IdFactory
}

export default function OutlineTree({ tree, onChange, selectedId = null, onSelect, newId }: Props) {
  const [collapsed, setCollapsed] = useState<ReadonlySet<string>>(new Set())
  const [renaming, setRenaming] = useState<string | null>(null)
  const [draft, setDraft] = useState('')

  const toggle = (id: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (!next.delete(id)) next.add(id)
      return next
    })

  function startRename(n: ResumeNode) {
    setRenaming(n.id)
    setDraft(n.title)
  }

  function commitRename(e: FormEvent) {
    e.preventDefault()
    if (renaming) onChange(renameNode(tree, renaming, draft))
    setRenaming(null)
  }

  function renderNode(n: ResumeNode) {
    const label = n.title || OUTLINE_LABELS.untitled
    const isCollapsed = collapsed.has(n.id)
    return (
      <li key={n.id} role="treeitem" aria-expanded={n.children.length ? !isCollapsed : undefined} aria-selected={selectedId === n.id}>
        {n.children.length > 0 && (
          <button type="button" aria-label={`${isCollapsed ? 'Expand' : 'Collapse'} ${label}`} onClick={() => toggle(n.id)}>
            {isCollapsed ? '+' : '-'}
          </button>
        )}
        {renaming === n.id ? (
          <form onSubmit={commitRename} style={{ display: 'inline' }}>
            <input aria-label={`New name for ${label}`} value={draft} onChange={(e) => setDraft(e.target.value)} autoFocus />
            <button type="submit">Save</button>
            <button type="button" onClick={() => setRenaming(null)}>Cancel</button>
          </form>
        ) : (
          <>
            <button type="button" onClick={() => onSelect?.(n.id)}>{label}</button>
            <button type="button" aria-label={`Rename ${label}`} onClick={() => startRename(n)}>Rename</button>
            {!isFixed(n) && (
              <>
                <button type="button" aria-label={`Move up ${label}`} disabled={!canMove(tree, n.id, -1)} onClick={() => onChange(moveNode(tree, n.id, -1))}>Up</button>
                <button type="button" aria-label={`Move down ${label}`} disabled={!canMove(tree, n.id, 1)} onClick={() => onChange(moveNode(tree, n.id, 1))}>Down</button>
                <button type="button" aria-label={`Delete ${label}`} onClick={() => onChange(deleteNode(tree, n.id))}>Delete</button>
              </>
            )}
            {allowedChildTypes(n.type).map((t) => (
              <button key={t} type="button" aria-label={`${OUTLINE_LABELS.add[t]} under ${label}`} onClick={() => onChange(addNode(tree, n.id, t, newId))}>
                {OUTLINE_LABELS.add[t]}
              </button>
            ))}
          </>
        )}
        {n.children.length > 0 && !isCollapsed && <ul role="group">{n.children.map(renderNode)}</ul>}
      </li>
    )
  }

  return (
    <div>
      <ul role="tree" aria-label="Resume outline">
        {renderNode(tree.header)}
        {tree.sections.map(renderNode)}
        {renderNode(tree.footer)}
      </ul>
      <button type="button" onClick={() => onChange(addNode(tree, null, 'section', newId))}>{OUTLINE_LABELS.add.section}</button>
    </div>
  )
}

