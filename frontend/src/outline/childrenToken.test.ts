import { createDummyMasterTree } from '../dummy/dummyMasterResume'
import { addNode, childrenTokenProblems, deleteNode, ensureChildrenToken, findNode, moveNode, setNodeLatex } from './treeOps'
import type { ResumeNode, ResumeTree } from './types'

const id = () => 'new'
const allLatex = (t: ResumeTree): string[] => {
  const walk = (n: ResumeNode): string[] => [n.id + ':' + n.latex, ...n.children.flatMap(walk)]
  return [t.header, ...t.sections, t.footer].flatMap(walk).sort()
}

describe('children token', () => {
  it('dummy master tree is consistent with the contract', () => {
    const t = createDummyMasterTree()
    for (const s of t.sections) {
      const walk = (n: ResumeNode) => {
        expect(childrenTokenProblems(n)).toEqual([])
        n.children.forEach(walk)
      }
      walk(s)
    }
  })

  it('adding the first child inserts a token when the parent has none', () => {
    let t = createDummyMasterTree()
    t = addNode(t, 'x1', 'subsection_text', () => 'b-a')
    t = setNodeLatex(deleteNode(deleteNode(t, 'x1b1'), 'x1b2'), 'x1', '\\resumeSubheading{A}{B}{C}{D}')
    t = deleteNode(t, 'b-a')
    expect(findNode(t, 'x1')!.node.children).toHaveLength(0)
    const t2 = addNode(t, 'x1', 'subsection_text', id)
    expect(findNode(t2, 'x1')!.node.latex).toBe('\\resumeSubheading{A}{B}{C}{D}\n{{children}}')
    expect(childrenTokenProblems(findNode(t2, 'x1')!.node)).toEqual([])
  })

  it('adding a child leaves a parent that already has the token unchanged', () => {
    const t = createDummyMasterTree()
    const before = findNode(t, 'x1')!.node.latex
    expect(findNode(addNode(t, 'x1', 'subsection_text', id), 'x1')!.node.latex).toBe(before)
  })

  it('reordering children never edits any latex', () => {
    const t = createDummyMasterTree()
    const moved = moveNode(moveNode(moveNode(t, 'x1', 1), 'x1b1', 1), 's-edu', 1)
    expect(allLatex(moved)).toEqual(allLatex(t))
    expect(moved).not.toEqual(t)
  })

  it('deleting the last child leaves the parent latex (token is stripped by the server) and flags nothing', () => {
    const t = deleteNode(createDummyMasterTree(), 'x2b1')
    expect(findNode(t, 'x2')!.node.latex).toBe(findNode(createDummyMasterTree(), 'x2')!.node.latex)
    expect(childrenTokenProblems(findNode(t, 'x2')!.node)).toEqual([])
  })

  it('reports missing, duplicate and unknown tokens (case-sensitive)', () => {
    const parent = (latex: string): ResumeNode => ({ id: 'p', type: 'section', title: '', latex, tags: [], children: [findNode(createDummyMasterTree(), 'e1')!.node] })
    expect(childrenTokenProblems(parent('\\section{A}'))).toHaveLength(1)
    expect(childrenTokenProblems(parent('{{children}}{{children}}'))).toHaveLength(1)
    expect(childrenTokenProblems(parent('{{children}}{{Children}}'))).toHaveLength(1)
    expect(childrenTokenProblems({ ...parent('x'), children: [] })).toEqual([])
  })

  it('ensureChildrenToken is idempotent', () => {
    expect(ensureChildrenToken(ensureChildrenToken('a'))).toBe('a\n{{children}}')
    expect(ensureChildrenToken('')).toBe('{{children}}')
  })
})
