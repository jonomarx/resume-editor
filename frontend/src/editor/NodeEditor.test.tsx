import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { EditorView } from '@codemirror/view'
import { useState } from 'react'
import { createDummyMasterTree } from '../dummy/dummyMasterResume'
import { LATEX_SNIPPETS } from '../config/constants'
import { findNode } from '../outline/treeOps'
import type { ResumeTree } from '../outline/types'
import NodeEditor from './NodeEditor'
import { expandSnippet } from './snippets'

const changes: ResumeTree[] = []
const tree = () => changes[changes.length - 1]
function Harness({ initial }: { initial: string | null }) {
  const [t, setT] = useState(createDummyMasterTree)
  const onChange = (n: ResumeTree) => {
    changes.push(n)
    setT(n)
  }
  const [sel, setSel] = useState(initial)
  return (
    <>
      <button onClick={() => setSel('x1b2')}>pick other</button>
      <NodeEditor tree={t} selectedId={sel} onChange={onChange} />
    </>
  )
}
const view = () => EditorView.findFromDOM(document.querySelector('.cm-editor') as HTMLElement)!

describe('NodeEditor', () => {
  it('shows a hint when nothing is selected', () => {
    render(<Harness initial={null} />)
    expect(screen.getByText(/Select a node/)).toBeInTheDocument()
  })

  it("shows the selected node's latex and switches with selection", async () => {
    render(<Harness initial="x1b1" />)
    expect(view().state.doc.toString()).toBe('\\resumeItem{Built a thing}')
    await userEvent.click(screen.getByText('pick other'))
    expect(view().state.doc.toString()).toBe('\\resumeItem{Shipped another thing}')
  })

  it('writes edits into the tree node, only that node', () => {
    render(<Harness initial="x1b1" />)
    act(() => view().dispatch({ changes: { from: 0, to: view().state.doc.length, insert: 'new text' } }))
    expect(findNode(tree(), 'x1b1')!.node.latex).toBe('new text')
    expect(findNode(tree(), 'x1b2')!.node.latex).toBe('\\resumeItem{Shipped another thing}')
  })

  it('inserts a snippet at the cursor and places the cursor at the marker', async () => {
    render(<Harness initial="x1b2" />)
    act(() => view().dispatch({ changes: { from: 0, to: view().state.doc.length, insert: '' } }))
    await userEvent.click(screen.getByRole('button', { name: 'Bold' }))
    expect(findNode(tree(), 'x1b2')!.node.latex).toBe('\\textbf{}')
    expect(view().state.selection.main.head).toBe('\\textbf{'.length)
  })
})

describe('children token warning', () => {
  it('warns when a node with children has no token, and not otherwise', () => {
    const { unmount } = render(<Harness initial="x1" />)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    act(() => view().dispatch({ changes: { from: 0, to: view().state.doc.length, insert: '\\resumeSubheading{a}{b}{c}{d}' } }))
    expect(screen.getByRole('alert')).toHaveTextContent('no {{children}} token')
    unmount()
  })

  it('the Children snippet restores the token', async () => {
    render(<Harness initial="x1" />)
    act(() => view().dispatch({ changes: { from: 0, to: view().state.doc.length, insert: 'x' } }))
    await userEvent.click(screen.getByRole('button', { name: 'Children' }))
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('expandSnippet', () => {
  it('handles markers and their absence', () => {
    expect(expandSnippet('a$0b')).toEqual({ insert: 'ab', cursor: 1 })
    expect(expandSnippet('abc')).toEqual({ insert: 'abc', cursor: 3 })
  })
  it('every snippet has at most one marker', () => {
    for (const s of LATEX_SNIPPETS) expect(s.text.split('$0').length).toBeLessThanOrEqual(2)
  })
})
