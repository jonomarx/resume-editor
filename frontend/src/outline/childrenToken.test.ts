import { createSampleTree } from '../test/sampleTree'
import { addNode, childrenTokenProblems, deleteNode, findNode, moveNode, setNodeLatex } from './treeOps'
import type { ResumeNode, ResumeTree } from './types'

const id = (() => {
  let n = 0
  return () => `n${++n}`
})()
const latexOf = (t: ResumeTree, nid: string) => findNode(t, nid)!.node.latex
const allLatex = (t: ResumeTree): string[] => {
  const walk = (n: ResumeNode): string[] => [n.id + ':' + n.latex, ...n.children.flatMap(walk)]
  return [t.header, ...t.sections, t.footer].flatMap(walk).sort()
}
const SECTION_BLOCK = '\n\\resumeSubHeadingListStart\n{{children}}\n\\resumeSubHeadingListEnd'
const SUB_BLOCK = '\n\\resumeItemListStart\n{{children}}\n\\resumeItemListEnd'

describe('children token and list wrappers', () => {
  it('sample master tree is consistent: token only on nodes with children', () => {
    const t = createSampleTree()
    const walk = (n: ResumeNode) => {
      expect(childrenTokenProblems(n)).toEqual([])
      expect(n.latex.includes('{{children}}')).toBe(n.children.length > 0)
      n.children.forEach(walk)
    }
    t.sections.forEach(walk)
  })

  it('a new section gains wrapper and token with its first child only', () => {
    const t1 = addNode(createSampleTree(), null, 'section', () => 'sec')
    expect(latexOf(t1, 'sec')).toBe('\\section{New Section}')
    const t2 = addNode(t1, 'sec', 'section_text', id)
    expect(latexOf(t2, 'sec')).toBe('\\section{New Section}' + SECTION_BLOCK)
    const t3 = addNode(t2, 'sec', 'subsection', id)
    expect(latexOf(t3, 'sec')).toBe(latexOf(t2, 'sec'))
  })

  it('a new subsection gains the item wrapper with its first bullet only', () => {
    const t1 = addNode(createSampleTree(), 's-exp', 'subsection', () => 'sub')
    const heading = latexOf(t1, 'sub')
    expect(heading).toMatch(/^\\resumeSubheading\{Company Name\}.*\}$/)
    expect(heading).not.toContain('{{children}}')
    const t2 = addNode(t1, 'sub', 'subsection_text', id)
    expect(latexOf(t2, 'sub')).toBe(heading + SUB_BLOCK)
    expect(latexOf(addNode(t2, 'sub', 'subsection_text', id), 'sub')).toBe(heading + SUB_BLOCK)
    expect(childrenTokenProblems(findNode(t2, 'sub')!.node)).toEqual([])
  })

  it('keeps what the user wrote: an existing token is not duplicated', () => {
    let t = addNode(createSampleTree(), null, 'section', () => 'sec')
    t = setNodeLatex(t, 'sec', '\\section{Mine}\n{{children}}')
    expect(latexOf(addNode(t, 'sec', 'section_text', id), 'sec')).toBe('\\section{Mine}\n{{children}}')
  })

  it('deleting the last child removes untouched wrapper and token, but not while other children remain', () => {
    let t = addNode(createSampleTree(), null, 'section', () => 'sec')
    t = addNode(addNode(t, 'sec', 'section_text', () => 'a'), 'sec', 'section_text', () => 'b')
    t = deleteNode(t, 'a')
    expect(latexOf(t, 'sec')).toBe('\\section{New Section}' + SECTION_BLOCK)
    t = deleteNode(t, 'b')
    expect(latexOf(t, 'sec')).toBe('\\section{New Section}')
  })

  it('deleting the last child keeps edited latex and warns about the empty wrapper', () => {
    let t = addNode(createSampleTree(), null, 'section', () => 'sec')
    t = addNode(t, 'sec', 'section_text', () => 'a')
    t = setNodeLatex(t, 'sec', latexOf(t, 'sec') + '\n% my note')
    t = deleteNode(t, 'a')
    expect(latexOf(t, 'sec')).toContain('% my note')
    expect(latexOf(t, 'sec')).toContain('{{children}}')
    expect(childrenTokenProblems(findNode(t, 'sec')!.node)).toHaveLength(1)
  })

  it('reordering never edits any latex', () => {
    const t = createSampleTree()
    const moved = moveNode(moveNode(moveNode(t, 'x1', 1), 'x1b1', 1), 's-edu', 1)
    expect(allLatex(moved)).toEqual(allLatex(t))
    expect(moved).not.toEqual(t)
  })

  it('a parent with children but no token still warns', () => {
    const t = setNodeLatex(createSampleTree(), 'x1', '\\resumeSubheading{a}{b}{c}{d}')
    expect(childrenTokenProblems(findNode(t, 'x1')!.node)).toHaveLength(1)
  })

  it('reports duplicate and unknown tokens (case-sensitive)', () => {
    const parent = (latex: string): ResumeNode => ({ id: 'p', type: 'section', title: '', latex, tags: [], children: [findNode(createSampleTree(), 'e1')!.node] })
    expect(childrenTokenProblems(parent('{{children}}{{children}}'))).toHaveLength(1)
    expect(childrenTokenProblems(parent('{{children}}{{Children}}'))).toHaveLength(1)
  })
})
