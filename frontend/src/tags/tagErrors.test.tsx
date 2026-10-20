import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { createHttpApi } from '../api/http'
import { TAG_ERRORS } from '../config/constants'
import MasterResumePage from '../pages/MasterResumePage'
import { createFakeServer, type FakeServer } from '../test/fakeServer'

vi.mock('../preview/PdfViewer', () => ({ default: () => null }))

let server: FakeServer

async function tryCreate(name = 'Rust') {
  render(<MasterResumePage apiClient={createHttpApi(server.fetch)} />)
  await userEvent.click(await screen.findByRole('button', { name: 'Built a thing' }))
  await userEvent.type(screen.getByLabelText('Add a tag'), name)
  await userEvent.click(screen.getByRole('button', { name: `Create "${name}"` }))
  return screen.findByRole('button', { name: 'Tag problem' })
}

beforeEach(() => {
  server = createFakeServer()
})

const cases: [string, { status: number; body: unknown } | 'network', string][] = [
  ['400 with the server detail', { status: 400, body: { code: 'validation_failed', message: 'bad', details: [{ field: 'name', message: 'must contain a letter or digit' }] } }, TAG_ERRORS.invalidName('must contain a letter or digit')],
  ['401', { status: 401, body: { code: 'unauthorized', message: 'Sign in required' } }, TAG_ERRORS.signedOut],
  ['403 csrf_invalid after the automatic retry', { status: 403, body: { code: 'csrf_invalid', message: 'bad token' } }, TAG_ERRORS.csrf],
  ['409 naming the conflicting slug', { status: 409, body: { code: 'alias_conflict', message: 'x', conflictingSlug: 'java' } }, TAG_ERRORS.conflict('java')],
  ['409 without a slug', { status: 409, body: { code: 'alias_conflict', message: 'x' } }, TAG_ERRORS.conflict()],
  ['429', { status: 429, body: { code: 'rate_limited', message: 'slow' } }, TAG_ERRORS.rateLimited],
  ['503', { status: 503, body: { code: 'renderer_unavailable', message: 'down' } }, TAG_ERRORS.unavailable],
  ['network error', 'network', TAG_ERRORS.network],
  ['any other status', { status: 500, body: { code: 'server_error', message: 'Boom' } }, TAG_ERRORS.generic],
]

describe('tag editor errors', () => {
  it.each(cases)('shows the specific message for %s', async (_name, failure, text) => {
    server.tags.failCreate = failure
    const indicator = await tryCreate()
    await userEvent.hover(indicator)
    expect(screen.getByRole('tooltip')).toHaveTextContent(text)
  })

  it('offers a sign-in link on 401 only', async () => {
    server.tags.failCreate = { status: 401, body: { code: 'unauthorized', message: 'x' } }
    await userEvent.hover(await tryCreate())
    expect(screen.getByRole('link', { name: 'Sign in' })).toHaveAttribute('href', '/login')
  })

  it('links the tooltip to the input and the indicator, hidden until hover or focus', async () => {
    server.tags.failCreate = { status: 503, body: { code: 'x', message: 'y' } }
    const indicator = await tryCreate()
    const input = screen.getByLabelText('Add a tag')
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
    await userEvent.hover(indicator)
    const tip = screen.getByRole('tooltip')
    expect(input).toHaveAttribute('aria-describedby', tip.id)
    expect(indicator).toHaveAttribute('aria-describedby', tip.id)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    await userEvent.unhover(indicator)
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('opens on keyboard focus and closes with Escape', async () => {
    server.tags.failCreate = { status: 503, body: { code: 'x', message: 'y' } }
    const indicator = await tryCreate()
    indicator.focus()
    expect(await screen.findByRole('tooltip')).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('tooltip')).not.toBeInTheDocument()
  })

  it('clears the error when a later action succeeds, and never blocks other edits', async () => {
    server.tags.failCreate = { status: 503, body: { code: 'x', message: 'y' } }
    await tryCreate()
    // other edits still work while the error is shown
    await userEvent.click(screen.getByRole('button', { name: 'Rename Education' }))
    expect(screen.getByLabelText('New name for Education')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    server.tags.failCreate = null
    await userEvent.click(screen.getByRole('button', { name: 'Create "Rust"' }))
    expect(screen.queryByRole('button', { name: 'Tag problem' })).not.toBeInTheDocument()
    expect(screen.getByLabelText('Add a tag')).not.toHaveAttribute('aria-invalid')
    expect(screen.getByRole('region', { name: 'Tags' })).toHaveTextContent('Rust')
  })
})
