import { act, fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, vi } from 'vitest'
import { ApiError, type Api, type IngestApi } from '../api/types'
import { INGEST_LABELS as L, INGEST_POLL_MAX_ATTEMPTS, INGEST_POLL_MS } from '../config/constants'
import { createDummyIngest } from '../dummy/dummyIngest'
import IngestPage from './IngestPage'

let user: ReturnType<typeof userEvent.setup>
const settle = (ms: number) => act(async () => void (await vi.advanceTimersByTimeAsync(ms)))

beforeEach(() => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
})
afterEach(() => vi.useRealTimers())

function setup(ingest: IngestApi = createDummyIngest()) {
  render(<IngestPage apiClient={{ ingest } as unknown as Api} />)
  return ingest
}
const paste = (text: string) => fireEvent.change(screen.getByLabelText('Posting'), { target: { value: text } })

describe('paste box (against the dummy ingest API)', () => {
  it('cannot submit an empty paste', () => {
    setup()
    expect(screen.getByRole('button', { name: L.submit })).toBeDisabled()
  })

  it('sends the paste, follows the event and reports done', async () => {
    const ingest = setup()
    const spy = vi.spyOn(ingest, 'ingest')
    paste('Software Engineering Intern at Acme')
    await user.selectOptions(screen.getByLabelText(L.kindLabel), 'text')
    await user.type(screen.getByLabelText(L.hintLabel), 'https://acme.example/p')
    await user.click(screen.getByRole('button', { name: L.submit }))
    expect(spy).toHaveBeenCalledWith('text', 'Software Engineering Intern at Acme', 'https://acme.example/p')
    expect(await screen.findByText(L.queued)).toBeInTheDocument()
    await settle(INGEST_POLL_MS * 3)
    expect(screen.getByText(L.done)).toBeInTheDocument()
  })

  it('says so when the posting was already added', async () => {
    setup()
    paste('Same posting')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS * 3)
    await user.click(screen.getByRole('button', { name: L.submit }))
    expect(await screen.findByText(L.duplicate)).toBeInTheDocument()
  })

  it('shows a failed event with its message', async () => {
    setup()
    await user.selectOptions(screen.getByLabelText(L.kindLabel), 'url')
    paste('https://nowhere.example/fail')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS * 3)
    expect(screen.getByText(L.failed('The page could not be fetched'))).toBeInTheDocument()
  })

  it.each([
    [413, L.tooLarge],
    [429, L.tooMany],
  ])('shows the message for a %i response', async (status, text) => {
    const ingest = createDummyIngest()
    ingest.ingest = () => Promise.reject(new ApiError(status, { code: 'x', message: 'x' }))
    setup(ingest)
    paste('anything')
    await user.click(screen.getByRole('button', { name: L.submit }))
    expect(await screen.findByRole('alert')).toHaveTextContent(text)
  })

  it('shows the server detail on a 400', async () => {
    const ingest = createDummyIngest()
    ingest.ingest = () => Promise.reject(new ApiError(400, { code: 'validation_failed', message: 'Request failed validation', details: [{ field: 'payload', message: 'host is not allowed' }] }))
    setup(ingest)
    paste('http://10.0.0.1/x')
    await user.click(screen.getByRole('button', { name: L.submit }))
    expect(await screen.findByRole('alert')).toHaveTextContent('host is not allowed')
  })

  it('gives up polling after the maximum number of checks', async () => {
    const ingest = createDummyIngest()
    ingest.getEvent = vi.fn(async (id: number) => ({ id, kind: 'text' as const, status: 'processing' as const, receivedAt: '', finishedAt: null, jobId: null, error: null }))
    setup(ingest)
    paste('slow one')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS * (INGEST_POLL_MAX_ATTEMPTS + 2))
    expect(ingest.getEvent).toHaveBeenCalledTimes(INGEST_POLL_MAX_ATTEMPTS)
    expect(screen.getByText(L.gaveUp)).toBeInTheDocument()
  })
})

describe('polling cancellation', () => {
  const slow = () => {
    const ingest = createDummyIngest()
    const getEvent = vi.fn(async (id: number) => ({ id, kind: 'text' as const, status: 'processing' as const, receivedAt: '', finishedAt: null, jobId: null, error: null }))
    ingest.getEvent = getEvent
    return { ingest, getEvent }
  }

  it('a second submit stops following the first event', async () => {
    const { ingest, getEvent } = slow()
    setup(ingest)
    paste('first posting')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS + 10)
    expect(getEvent.mock.calls.map((c) => c[0])).toEqual([1])
    paste('second posting')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS * 3 + 10)
    const ids = getEvent.mock.calls.map((c) => c[0])
    expect(ids.slice(1).every((id) => id === 2)).toBe(true)
    expect(ids).toContain(2)
  })

  it('unmounting stops all polling', async () => {
    const { ingest, getEvent } = slow()
    const { unmount } = render(<IngestPage apiClient={{ ingest } as unknown as Api} />)
    paste('a posting')
    await user.click(screen.getByRole('button', { name: L.submit }))
    await settle(INGEST_POLL_MS + 10)
    const before = getEvent.mock.calls.length
    unmount()
    await settle(INGEST_POLL_MS * 5)
    expect(getEvent.mock.calls.length).toBe(before)
  })
})

describe('bookmarklet generator (against the dummy ingest API)', () => {
  it('creates a token, shows the secret and code once, and never stores it', async () => {
    const store = vi.spyOn(Storage.prototype, 'setItem')
    const ingest = setup()
    const create = vi.spyOn(ingest, 'createToken')
    await user.click(screen.getByRole('button', { name: L.createToken }))
    expect(create).toHaveBeenCalledWith(L.tokenDefaultLabel)
    const code = (await screen.findByLabelText(L.codeLabel)) as HTMLTextAreaElement
    expect(code.value).toMatch(/^javascript:\(function\(\)\{/)
    expect(code.value).toContain(window.location.origin + '/api/ingest')
    expect(code.value).toContain('Bearer dummy-ingest-1-')
    expect(screen.getByText(L.tokenSecretNote)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: L.bookmarkletLink }).getAttribute('href')).toMatch(/^javascript:/)
    expect(store).not.toHaveBeenCalled()
  })

  it('does not create a token with a blank name', async () => {
    setup()
    await user.clear(screen.getByLabelText(L.tokenLabelLabel))
    await user.type(screen.getByLabelText(L.tokenLabelLabel), '   ')
    expect(screen.getByRole('button', { name: L.createToken })).toBeDisabled()
  })

  it('lists tokens without their secret and revokes one', async () => {
    const ingest = setup()
    await ingest.createToken('Old laptop')
    await user.click(screen.getByRole('button', { name: L.createToken }))
    expect(await screen.findByRole('listitem', { name: 'Old laptop' })).toBeInTheDocument()
    expect(screen.queryByText(/dummy-ingest-1/)).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: 'Revoke Old laptop' }))
    expect(await screen.findByText(/Old laptop \(revoked\)/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Revoke Old laptop' })).not.toBeInTheDocument()
  })

  it('the secret is gone after the page is shown again', async () => {
    const ingest = createDummyIngest()
    const { unmount } = render(<IngestPage apiClient={{ ingest } as unknown as Api} />)
    await user.click(screen.getByRole('button', { name: L.createToken }))
    await screen.findByLabelText(L.codeLabel)
    unmount()
    render(<IngestPage apiClient={{ ingest } as unknown as Api} />)
    expect(await screen.findByRole('listitem', { name: L.tokenDefaultLabel })).toBeInTheDocument()
    expect(screen.queryByLabelText(L.codeLabel)).not.toBeInTheDocument()
  })
})
