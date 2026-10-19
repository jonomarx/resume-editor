import { EDITOR_LABELS } from '../config/constants'
import { findNode, setNodeLatex } from '../outline/treeOps'
import type { ResumeTree } from '../outline/types'
import LatexEditor from './LatexEditor'

interface Props {
  tree: ResumeTree
  selectedId: string | null
  onChange(tree: ResumeTree): void
}

/** Edits only the selected node's own `latex`; the template preamble is never part of the tree. */
export default function NodeEditor({ tree, selectedId, onChange }: Props) {
  const found = selectedId ? findNode(tree, selectedId) : null
  if (!found) return <p>{EDITOR_LABELS.empty}</p>
  const { node } = found
  return <LatexEditor key={node.id} value={node.latex} onChange={(latex) => onChange(setNodeLatex(tree, node.id, latex))} />
}
