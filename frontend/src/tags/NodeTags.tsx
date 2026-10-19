import type { Api } from '../api/types'
import { TAG_LABELS } from '../config/constants'
import { findNode, setNodeTags } from '../outline/treeOps'
import type { ResumeTree } from '../outline/types'
import TagEditor from './TagEditor'
import { useTags } from './useTags'

interface Props {
  api: Api
  tree: ResumeTree
  selectedId: string | null
  onChange(tree: ResumeTree): void
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
      <TagEditor key={node.id} selected={node.tags} vocabulary={tags} onChange={(slugs) => onChange(setNodeTags(tree, node.id, slugs))} onCreate={create} />
    </>
  )
}
