import type { Api } from '../api/types'
import { TAG_LABELS } from '../config/constants'
import { addNodeTag, findNode, removeNodeTag } from '../outline/treeOps'
import type { ResumeTree } from '../outline/types'
import TagEditor from './TagEditor'
import { useTags } from './useTags'

interface Props {
  api: Api
  tree: ResumeTree
  selectedId: string | null
  /** Accepts an updater so changes are applied to the newest tree, even after an async create. */
  onChange(update: (tree: ResumeTree) => ResumeTree): void
}

/** Tag editor for the selected node (header and footer carry no tags). */
export default function NodeTags({ api, tree, selectedId, onChange }: Props) {
  const { tags, error, create } = useTags(api)
  const found = selectedId ? findNode(tree, selectedId) : null
  if (!found || !(TAG_LABELS.taggable as readonly string[]).includes(found.node.type)) return null
  const { node } = found
  return (
    <>
      {error && <p role="alert">{error}</p>}
      <TagEditor key={node.id} selected={node.tags} vocabulary={tags} onAdd={(slug) => onChange((t) => addNodeTag(t, node.id, slug))} onRemove={(slug) => onChange((t) => removeNodeTag(t, node.id, slug))} onCreate={create} />
    </>
  )
}
