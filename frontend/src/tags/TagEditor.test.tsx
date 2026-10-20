import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { createHttpApi } from '../api/http'
import MasterResumePage from '../pages/MasterResumePage'
import { TAG_SUGGESTION_LIMIT } from '../config/constants'
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
    const panel = await openNode('Built a thing')
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
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'jdk')
    await userEvent.click(await screen.findByRole('button', { name: 'Java' }))
    await userEvent.type(screen.getByLabelText('Add a tag'), 'jdk')
    expect(screen.queryByRole('button', { name: 'Java' })).not.toBeInTheDocument()
  })

  it('removes a tag', async () => {
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'python')
    await userEvent.click(await screen.findByRole('button', { name: 'Python' }))
    await userEvent.click(screen.getByRole('button', { name: 'Remove tag Python' }))
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('No tags yet.')
  })

  it('creates a new vocabulary entry first, then attaches its slug', async () => {
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Rust')
    await userEvent.selectOptions(screen.getByLabelText('Category for a new tag'), 'language')
    await userEvent.click(screen.getByRole('button', { name: 'Create "Rust"' }))
    expect(server.tags.vocabulary.map((t) => t.slug)).toContain('rust')
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('Rust')
  })

  it('creating a name that matches an existing alias attaches that tag instead of a duplicate', async () => {
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Jdk ')
    // "jdk " matches the alias of Java in the list; the create button also appears for non-exact names
    await userEvent.click(screen.getByRole('button', { name: 'Create "Jdk"' }))
    expect(server.tags.vocabulary.filter((t) => t.slug === 'jdk')).toHaveLength(0)
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('Java')
  })

  it('writes slugs (not names) into the tree that gets saved', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      await openNode('Built a thing')
      await userEvent.type(screen.getByLabelText('Add a tag'), 'java')
      await userEvent.click(await screen.findByRole('button', { name: 'Java' }))
      await vi.advanceTimersByTimeAsync(2500)
      expect(lastSavedTags('x1b1')).toEqual(['java'])
    } finally {
      vi.useRealTimers()
    }
  })

  it('shows no tag editor for header, footer, sections or subsections', async () => {
    render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
    for (const name of ['Your Name', 'Footer', 'Education', 'Acme Corp']) {
      await userEvent.click(await screen.findByRole('button', { name }))
      expect(screen.queryByRole('region', { name: 'Tags' })).not.toBeInTheDocument()
    }
    await userEvent.click(screen.getByRole('button', { name: 'University Name, B.S. Computer Science' }))
    expect(screen.getByRole('region', { name: 'Tags' })).toBeInTheDocument()
  })

  it('finds a tag by a symbol alias such as C++', async () => {
    server.tags.vocabulary.push({ id: 9, name: 'C', slug: 'c', category: 'language', aliases: ['C++'] })
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'c++')
    await userEvent.click(await screen.findByRole('button', { name: 'C' }))
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('C')
  })

  it('lists at most TAG_SUGGESTION_LIMIT suggestions', async () => {
    for (let i = 0; i < 20; i++) server.tags.vocabulary.push({ id: 100 + i, name: `Lib ${i}`, slug: `lib-${i}`, category: 'framework', aliases: [] })
    await openNode('Built a thing')
    await userEvent.type(screen.getByLabelText('Add a tag'), 'lib')
    expect(await screen.findAllByRole('button', { name: /^Lib / })).toHaveLength(TAG_SUGGESTION_LIMIT)
  })

  it('does not overwrite an edit made while the create request is in flight', async () => {
    let release!: () => void
    const gate = new Promise<void>((r) => (release = r))
    const slow = (async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input) === '/api/tags' && init?.method === 'POST') await gate
      return server.fetch(input, init)
    }) as typeof fetch
    render(<MasterResumePage apiClient={createHttpApi(slow)} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Built a thing' }))
    await userEvent.type(screen.getByLabelText('Add a tag'), 'Rust')
    await userEvent.click(screen.getByRole('button', { name: 'Create "Rust"' }))
    // while the create is pending: rename a section and switch to another node
    await userEvent.click(screen.getByRole('button', { name: 'Rename Education' }))
    const input = screen.getByLabelText('New name for Education')
    await userEvent.clear(input)
    await userEvent.type(input, 'Schooling')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    await userEvent.click(screen.getByRole('button', { name: 'Fixed a bug' }))
    release()
    // back on the original node: the tag landed there, and the rename survived
    await userEvent.click(await screen.findByRole('button', { name: 'Built a thing' }))
    expect(await screen.findByRole('region', { name: 'Tags' })).toHaveTextContent('Rust')
    expect(screen.getByRole('button', { name: 'Rename Schooling' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Fixed a bug' }))
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('No tags yet.')
  })
})
