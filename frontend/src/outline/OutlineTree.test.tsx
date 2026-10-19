import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { createDummyMasterTree } from '../dummy/dummyMasterResume'
import { PLACEHOLDERS } from '../config/constants'
import OutlineTree from './OutlineTree'
import { addNode, deleteNode, moveNode } from './treeOps'
import type { ResumeTree } from './types'

let n = 0
const newId = () => `new-${++n}`

const changes: ResumeTree[] = []
const latest = () => changes[changes.length - 1]
function Harness() {
  const [tree, setTree] = useState(createDummyMasterTree)
  const onChange = (t: ResumeTree) => {
    changes.push(t)
    setTree(t)
  }
  return <OutlineTree tree={tree} onChange={onChange} newId={newId} />
}

describe('OutlineTree component', () => {
  it('adds a section with placeholder title', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Add section' }))
    expect(screen.getByRole('button', { name: PLACEHOLDERS.section.title })).toBeInTheDocument()
    expect(latest().sections.at(-1)!.title).toBe(PLACEHOLDERS.section.title)
  })

  it('adds a subsection and a bullet at the right levels', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Add subsection under Experience' }))
    expect(latest().sections[1].children.at(-1)!.type).toBe('subsection')
    await userEvent.click(screen.getByRole('button', { name: 'Add bullet under Globex' }))
    expect(latest().sections[1].children[1].children).toHaveLength(2)
  })

  it('offers no way to nest below a subsection or under a bullet', () => {
    render(<Harness />)
    expect(screen.queryByRole('button', { name: 'Add subsection under Acme Corp' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Add .* under Built a thing$/ })).not.toBeInTheDocument()
  })

  it('renames a node', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Rename Education' }))
    const input = screen.getByLabelText('New name for Education')
    await userEvent.clear(input)
    await userEvent.type(input, 'Schooling')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(screen.getByRole('button', { name: 'Schooling' })).toBeInTheDocument()
    expect(latest().sections[0].title).toBe('Schooling')
  })

  it('reorders with up and down, disabling the ends', async () => {
    render(<Harness />)
    expect(screen.getByRole('button', { name: 'Move up Education' })).toBeDisabled()
    await userEvent.click(screen.getByRole('button', { name: 'Move down Education' }))
    expect(latest().sections.map((s) => s.title)).toEqual(['Experience', 'Education'])
    expect(screen.getByRole('button', { name: 'Move down Education' })).toBeDisabled()
  })

  it('deletes a node with its children, but not header or footer', async () => {
    render(<Harness />)
    await userEvent.click(screen.getByRole('button', { name: 'Delete Experience' }))
    expect(latest().sections.map((s) => s.title)).toEqual(['Education'])
    expect(screen.queryByText('Acme Corp')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete Your Name' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete Footer' })).not.toBeInTheDocument()
  })

  it('collapses and expands children', async () => {
    render(<Harness />)
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Collapse Experience' }))
    expect(screen.queryByText('Acme Corp')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Expand Experience' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Expand Experience' }))
    expect(screen.getByText('Acme Corp')).toBeInTheDocument()
  })
})

describe('treeOps', () => {
  it('rejects shapes that break the one-level subsection limit', () => {
    const t = createDummyMasterTree()
    expect(() => addNode(t, 'x1', 'subsection')).toThrow()
    expect(() => addNode(t, 's-exp', 'subsection_text')).toThrow()
    expect(() => addNode(t, null, 'subsection')).toThrow()
  })

  it('refuses to delete or move header and footer, and does not mutate input', () => {
    const t = createDummyMasterTree()
    const copy = structuredClone(t)
    expect(() => deleteNode(t, 'h')).toThrow()
    expect(moveNode(t, 'f', -1)).toBe(t)
    moveNode(t, 'x1', 1)
    expect(t).toEqual(copy)
  })

  it('moves a subsection within its section only', () => {
    const t = moveNode(createDummyMasterTree(), 'x1', 1)
    expect(t.sections[1].children.map((c) => c.id)).toEqual(['x2', 'x1'])
    expect(moveNode(t, 'x1', 1)).toBe(t)
  })
})
