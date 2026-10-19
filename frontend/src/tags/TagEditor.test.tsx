import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { createHttpApi } from '../api/http'
import MasterResumePage from '../pages/MasterResumePage'
import { createFakeServer, type FakeServer } from '../test/fakeServer'

vi.mock('../preview/PdfViewer', () => ({ default: () => null }))

let server: FakeServer
const lastSavedTags = (id: string) => {
  const find = (n: { id: string; tags: string[]; children: never[] }): string[] | null =>
    n.id === id ? n.tags : n.children.map((c) => find(c as never)).find(Boolean) ?? null
  const trees = server.resume.saves
  return trees.length ? trees.at(-1)!.tree.sections.map((s) => find(s as never)).find(Boolean) : null
}

async function openNode(name: string) {
  render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
  await userEvent.click(await screen.findByRole('button', { name }))
  return screen.findByRole('region', { name: 'Tags' })
}

beforeEach(() => {
  server = createFakeServer()
})

describe('tag editor', () => {
  it('picks several tags from the vocabulary for a node', async () => {
    const panel = await openNode('Acme Corp')
    expect(panel).toHaveTextContent('No tags yet.')
    const input = screen.getByLabelText('Add a tag')
    await userEvent.type(input, 'ja')
    await userEvent.click(await screen.findByRole('button', { name: 'Java' }))
    await userEvent.type(input, 're')
    await userEvent.click(await screen.findByRole('button', { name: 'React' }))
    expect(panel).toHaveTextContent('Java')
    expect(panel).toHaveTextContent('React')
    expect(screen.queryByRole('button', { name: 'Java' })).not.toBeInTheDocument()
  })

  it('matches by alias and does not offer tags already on the node', async () => {
    await openNode('Acme Corp')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'jdk')
    await userEvent.click(await screen.findByRole('button', { name: 'Java' }))
    await userEvent.type(screen.getByLabelText('Add a tag'), 'jdk')
    expect(screen.queryByRole('button', { name: 'Java' })).not.toBeInTheDocument()
  })

  it('removes a tag', async () => {
    await openNode('Acme Corp')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'python')
    await userEvent.click(await screen.findByRole('button', { name: 'Python' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove tag Python' }))
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('No tags yet.')
  })

  it('creates a new vocabulary entry first, then attaches its slug', async () => {
    await openNode('Acme Corp')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Rust')
    await userEvent.selectOptions(screen.getByLabelText('Category for a new tag'), 'language')
    await userEvent.click(screen.getByRole('button', { name: 'Create "Rust"' }))
    expect(server.tags.vocabulary.map((t) => t.slug)).toContain('rust')
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('Rust')
  })

  it('creating a name that matches an existing alias attaches that tag instead of a duplicate', async () => {
    await openNode('Acme Corp')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Jdk ')
    // "jdk " matches the alias of Java in the list; the create button also appears for non-exact names
    await userEvent.click(screen.getByRole('button', { name: 'Create "Jdk"' }))
    expect(server.tags.vocabulary.filter((t) => t.slug === 'jdk')).toHaveLength(0)
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('Java')
  })

  it('shows an error when creation fails and does not tag the node', async () => {
    await openNode('Acme Corp')
    server.tags.failCreate = true
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Rust')
    await userEvent.click(screen.getByRole('button', { name: 'Create "Rust"' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not create')
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('No tags yet.')
  })

  it('writes slugs (not names) into the tree that gets saved', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      await openNode('Acme Corp')
      await userEvent.type(screen.getByLabelText('Add a tag'), 'java')
      await userEvent.click(await screen.findByRole('button', { name: 'Java' }))
      await vi.advanceTimersByTimeAsync(2500)
      expect(lastSavedTags('x1')).toEqual(['java'])
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows no tag editor for header or footer', async () => {
    render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Your Name' }))
    expect(screen.queryByRole('region', { name: 'Tags' })).not.toBeInTheDocument()
  })
})
