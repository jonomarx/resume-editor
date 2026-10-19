import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, vi } from 'vitest'
import { createHttpApi } from '../api/http'
import { AUTOSAVE_DEBOUNCE_MS } from '../config/constants'
import { createFakeServer, type FakeServer } from '../test/fakeServer'
import MasterResumePage from './MasterResumePage'

vi.mock('../preview/PdfViewer', () => ({ default: ({ url }: { url: string }) => <div data-testid="pdf" data-url={url} /> }))

let server: FakeServer
let user: ReturnType<typeof userEvent.setup>

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  server = createFakeServer()
  render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
})
afterEach(() => vi.useRealTimers())

const settle = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)))

async function rename(from: string, to: string) {
  await user.click(await screen.findByRole('button', { name: `Rename ${from}` }))
  const input = screen.getByLabelText(`New name for ${from}`)
  await user.clear(input)
  await user.type(input, to)
  await user.click(screen.getByRole('button', { name: 'Save' }))
}

describe('master resume autosave', () => {
  it('loads, then saves after the debounce with the loaded version, once', async () => {
    await rename('Education', 'Schooling')
    expect(screen.getByRole('status')).toHaveTextContent('Unsaved changes')
    await settle(AUTOSAVE_DEBOUNCE_MS - 100)
    expect(server.resume.saves).toHaveLength(0)
    await settle(200)
    expect(server.resume.saves).toHaveLength(1)
    expect(server.resume.saves[0].baseVersion).toBe(1)
    expect(server.resume.saves[0].tree.sections[0].title).toBe('Schooling')
    expect(screen.getByRole('status')).toHaveTextContent('All changes saved')
  })

  it('uses the version returned by the previous save for the next one', async () => {
    await rename('Education', 'A')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    await rename('A', 'B')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    expect(server.resume.saves.map((s) => s.baseVersion)).toEqual([1, 2])
  })

  it('on 409 stops saving and offers a reload that fetches the latest', async () => {
    await screen.findByRole('button', { name: 'Rename Education' })
    const other = structuredClone((await server.fetch('/api/resumes/master').then((r) => r.json())).tree)
    other.sections[0].title = 'Elsewhere'
    server.resume.saveElsewhere(other)
    await rename('Education', 'Mine')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    expect(await screen.findByRole('alert')).toHaveTextContent('changed somewhere else')
    await rename('Mine', 'Mine again')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    expect(server.resume.saves).toHaveLength(1)
    await user.click(screen.getByRole('button', { name: 'Reload latest version' }))
    expect(await screen.findByRole('button', { name: 'Rename Elsewhere' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent('All changes saved')
  })

  it('shows a save failure and retries', async () => {
    await screen.findByRole('button', { name: 'Rename Education' })
    server.resume.failNextSave()
    await rename('Education', 'X')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    expect(await screen.findByRole('alert')).toHaveTextContent('Saving failed')
    await user.click(screen.getByRole('button', { name: 'Retry save' }))
    expect(await screen.findByText('All changes saved')).toBeInTheDocument()
    expect(server.resume.saves.at(-1)!.tree.sections[0].title).toBe('X')
  })
})

describe('version history', () => {
  it('lists versions and restores an old one as a new version', async () => {
    await rename('Education', 'Changed')
    await settle(AUTOSAVE_DEBOUNCE_MS + 100)
    await user.click(screen.getByRole('button', { name: 'Version history' }))
    expect(await screen.findByText(/Version 2/)).toHaveTextContent('current')
    await user.click(screen.getByRole('button', { name: 'Restore version 1' }))
    expect(await screen.findByRole('button', { name: 'Rename Education' })).toBeInTheDocument()
    expect(await screen.findByText(/Version 3/)).toHaveTextContent('current')
    expect(within(screen.getByLabelText('Version history')).getAllByRole('listitem')).toHaveLength(3)
  })
})
