import { childrenBlock, CHILDREN_TOKEN, LIST_WRAPPERS, PLACEHOLDER_LATEX, PLACEHOLDERS, TOKEN_WARNINGS } from '../config/constants'
import type { NodeType, ResumeNode, ResumeTree } from './types'

/**
 * Pure tree operations for the outline. The shape rules come from DESIGN section 4:
 * header and footer are fixed top-level nodes; sections hold section text and subsections;
 * subsections hold subsection text only (one level of nesting, nothing deeper).
 */

/** Node types that may be added as a child of a node of the given type. */
export function allowedChildTypes(parent: NodeType | 'root'): NodeType[] {
  switch (parent) {
    case 'root':
      return ['section']
    case 'section':
      return ['section_text', 'subsection']
    case 'subsection':
      return ['subsection_text']
    default:
      return []
  }
}

export const isFixed = (n: ResumeNode) => n.type === 'header' || n.type === 'footer'

export type IdFactory = () => string
export const defaultId: IdFactory = () => crypto.randomUUID()

/** Problems the server would reject with 400 for this node's latex (api-contract ResumeNode). */
export function childrenTokenProblems(node: ResumeNode): string[] {
  const out: string[] = []
  const count = node.latex.split(CHILDREN_TOKEN).length - 1
  if (node.children.length > 0 && count === 0) out.push(TOKEN_WARNINGS.missing)
  if (count > 1) out.push(TOKEN_WARNINGS.duplicate)
  if (node.children.length === 0 && count === 1 && node.type in LIST_WRAPPERS) out.push(TOKEN_WARNINGS.emptyWrapper)
  for (const m of node.latex.matchAll(/\{\{[^{}]*\}\}/g)) {
    if (m[0] !== CHILDREN_TOKEN) out.push(TOKEN_WARNINGS.unknown(m[0]))
  }
  return out
}

export function createNode(type: NodeType, newId: IdFactory = defaultId): ResumeNode {
  const titles: Partial<Record<NodeType, string>> = {
    section: PLACEHOLDERS.section.title,
    section_text: PLACEHOLDERS.sectionText.text,
    subsection: PLACEHOLDERS.subsection.organization,
    subsection_text: PLACEHOLDERS.bullet,
  }
  const latex: Partial<Record<NodeType, string>> = {
    section: PLACEHOLDER_LATEX.section(PLACEHOLDERS),
    section_text: PLACEHOLDER_LATEX.sectionText(PLACEHOLDERS),
    subsection: PLACEHOLDER_LATEX.subsection(PLACEHOLDERS),
    subsection_text: PLACEHOLDER_LATEX.bullet(PLACEHOLDERS),
  }
  return { id: newId(), type, title: titles[type] ?? '', latex: latex[type] ?? '', tags: [], children: [] }
}

/** Finds a node anywhere in the tree, with its parent (null for top-level nodes). */
export function findNode(tree: ResumeTree, id: string): { node: ResumeNode; parent: ResumeNode | null } | null {
  const walk = (n: ResumeNode, parent: ResumeNode | null): { node: ResumeNode; parent: ResumeNode | null } | null => {
    if (n.id === id) return { node: n, parent }
    for (const c of n.children) {
      const r = walk(c, n)
      if (r) return r
    }
    return null
  }
  for (const n of [tree.header, ...tree.sections, tree.footer]) {
    const r = walk(n, null)
    if (r) return r
  }
  return null
}

const mapNode = (n: ResumeNode, id: string, f: (n: ResumeNode) => ResumeNode): ResumeNode =>
  n.id === id ? f(n) : { ...n, children: n.children.map((c) => mapNode(c, id, f)) }

const mapAll = (tree: ResumeTree, id: string, f: (n: ResumeNode) => ResumeNode): ResumeTree => ({
  ...tree,
  header: mapNode(tree.header, id, f),
  sections: tree.sections.map((s) => mapNode(s, id, f)),
  footer: mapNode(tree.footer, id, f),
})

export function renameNode(tree: ResumeTree, id: string, title: string): ResumeTree {
  return mapAll(tree, id, (n) => ({ ...n, title }))
}

export function setNodeLatex(tree: ResumeTree, id: string, latex: string): ResumeTree {
  return mapAll(tree, id, (n) => ({ ...n, latex }))
}

export function addNodeTag(tree: ResumeTree, id: string, slug: string): ResumeTree {
  return mapAll(tree, id, (n) => (n.tags.includes(slug) ? n : { ...n, tags: [...n.tags, slug] }))
}

export function removeNodeTag(tree: ResumeTree, id: string, slug: string): ResumeTree {
  return mapAll(tree, id, (n) => ({ ...n, tags: n.tags.filter((t) => t !== slug) }))
}

/** Adds a child to `parentId` (or a new section when parentId is null). Rejects shapes the model forbids. */
export function addNode(tree: ResumeTree, parentId: string | null, type: NodeType, newId: IdFactory = defaultId): ResumeTree {
  if (parentId === null) {
    if (!allowedChildTypes('root').includes(type)) throw new Error(`Cannot add ${type} at the top level`)
    return { ...tree, sections: [...tree.sections, createNode(type, newId)] }
  }
  const found = findNode(tree, parentId)
  if (!found) throw new Error(`No node ${parentId}`)
  if (!allowedChildTypes(found.node.type).includes(type)) {
    throw new Error(`Cannot add ${type} under ${found.node.type}`)
  }
  const child = createNode(type, newId)
  // The first child brings the list wrapper and token, appended after whatever the user already wrote.
  // Later children never touch the parent's latex.
  return mapAll(tree, parentId, (n) => ({
    ...n,
    latex: n.children.length === 0 && !n.latex.includes(CHILDREN_TOKEN) ? n.latex + childrenBlock(wrapperType(n)) : n.latex,
    children: [...n.children, child],
  }))
}

const wrapperType = (n: ResumeNode) => n.type as keyof typeof LIST_WRAPPERS

function filterTree(nodes: ResumeNode[], id: string): ResumeNode[] {
  return nodes.filter((n) => n.id !== id).map((n) => ({ ...n, children: filterTree(n.children, id) }))
}

export function deleteNode(tree: ResumeTree, id: string): ResumeTree {
  const found = findNode(tree, id)
  if (!found) return tree
  if (isFixed(found.node)) throw new Error('Header and footer cannot be deleted')
  const result = { ...tree, sections: filterTree(tree.sections, id) }
  const parent = found.parent
  if (parent && parent.children.length === 1 && parent.type in LIST_WRAPPERS) {
    // The last child is gone: take the wrapper back out, but only if it is still exactly what addNode inserted.
    const block = childrenBlock(wrapperType(parent))
    if (parent.latex.endsWith(block)) {
      return mapAll(result, parent.id, (n) => ({ ...n, latex: n.latex.slice(0, n.latex.length - block.length) }))
    }
  }
  return result
}

function swap(list: ResumeNode[], id: string, delta: -1 | 1): ResumeNode[] {
  const i = list.findIndex((n) => n.id === id)
  const j = i + delta
  if (i < 0 || j < 0 || j >= list.length) return list
  const copy = [...list]
  ;[copy[i], copy[j]] = [copy[j], copy[i]]
  return copy
}

/** Moves a node one place up or down among its siblings. Out-of-range moves return the tree unchanged. */
export function moveNode(tree: ResumeTree, id: string, delta: -1 | 1): ResumeTree {
  const found = findNode(tree, id)
  if (!found || !canMove(tree, id, delta)) return tree
  if (found.parent === null) return { ...tree, sections: swap(tree.sections, id, delta) }
  return mapAll(tree, found.parent.id, (p) => ({ ...p, children: swap(p.children, id, delta) }))
}

export function canMove(tree: ResumeTree, id: string, delta: -1 | 1): boolean {
  const found = findNode(tree, id)
  if (!found || isFixed(found.node)) return false
  const siblings = found.parent ? found.parent.children : tree.sections
  const j = siblings.findIndex((n) => n.id === id) + delta
  return j >= 0 && j < siblings.length
}
