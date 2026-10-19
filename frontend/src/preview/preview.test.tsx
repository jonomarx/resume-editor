import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, vi } from 'vitest'
import { createHttpApi } from '../api/http'
import { RENDER_DEBOUNCE_MS } from '../config/constants'
import MasterResumePage from '../pages/MasterResumePage'
import { createFakeServer, type FakeServer } from '../test/fakeServer'

vi.mock('./PdfViewer', () => ({ default: ({ url }: { url: string }) => <div data-testid="pdf" data-url={url} /> }))

let server: FakeServer
let user: ReturnType<typeof userEvent.setup>

beforeEach(async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
  server = createFakeServer()
})
afterEach(() => vi.useRealTimers())

const settle = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)))
const open = async () => {
  render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
  await screen.findByRole('button', { name: 'Rename Education' })
  await settle(10)
}
async function rename(from: string, to: string) {
  await user.click(screen.getByRole('button', { name: `Rename ${from}` }))
  const input = screen.getByLabelText(`New name for ${from}`)
  await user.clear(input)
  await user.type(input, to)
  await user.click(screen.getByRole('button', { name: 'Save' }))
}

describe('preview', () => {
  it('renders the loaded tree at once and shows the PDF and page count', async () => {
    await open()
    expect(server.render.calls).toHaveLength(1)
    expect(await screen.findByTestId('pdf')).toHaveAttribute('data-url', expect.stringMatching(/^\/api\/render\/0+1\/pdf$/))
    expect(screen.getByTestId('page-count')).toHaveTextContent('1 page')
  })

  it('waits RENDER_DEBOUNCE_MS after the last edit, rendering the edited tree once', async () => {
    await open()
    await rename('Education', 'A')
    await settle(RENDER_DEBOUNCE_MS - 500)
    await rename('A', 'B')
    await settle(RENDER_DEBOUNCE_MS - 500)
    expect(server.render.calls).toHaveLength(1)
    await settle(1000)
    expect(server.render.calls).toHaveLength(2)
    expect(server.render.calls[1].sections[0].title).toBe('B')
  })

  it('Render now skips the wait', async () => {
    await open()
    await rename('Education', 'Quick')
    await user.click(screen.getByRole('button', { name: 'Render now' }))
    await settle(10)
    expect(server.render.calls).toHaveLength(2)
    expect(server.render.calls[1].sections[0].title).toBe('Quick')
    await settle(RENDER_DEBOUNCE_MS + 100)
    expect(server.render.calls).toHaveLength(2)
  })

  it('shows compile errors mapped to node titles and hides the PDF', async () => {
    await open()
    server.render.nextResult = {
      success: false, contentHash: 'f'.repeat(64), cached: false, pageCount: null, pdfUrl: null,
      log: '! Undefined control sequence.', errors: [{ nodeId: 's-edu', line: 2, message: 'Undefined control sequence' }],
    }
    await user.click(screen.getByRole('button', { name: 'Render now' }))
    const alert = await screen.findByText(/Could not render/)
    expect(alert.closest('[role=alert]')).toHaveTextContent('Education, line 2: Undefined control sequence')
    expect(screen.queryByTestId('pdf')).not.toBeInTheDocument()
    expect(screen.queryByTestId('page-count')).not.toBeInTheDocument()
  })

  it('shows server errors such as 503', async () => {
    await open()
    server.render.nextResult = { status: 503, code: 'renderer_unavailable', message: 'The renderer did not respond in time' }
    await user.click(screen.getByRole('button', { name: 'Render now' }))
    expect(await screen.findByText(/did not respond in time/)).toBeInTheDocument()
  })
})
