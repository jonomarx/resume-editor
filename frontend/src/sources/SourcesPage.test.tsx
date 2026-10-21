import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { vi } from 'vitest'
import { createHttpApi } from '../api/http'
import { createFakeFetch } from '../test/fakeFetch'
import { createFakeSources } from '../test/fakeSources'
import SourcesPage from './SourcesPage'

function setup(sources = createFakeSources()) {
  render(<SourcesPage apiClient={createHttpApi(createFakeFetch({ sources }))} />)
  return sources
}
const row = (name: string) => screen.findByRole('listitem', { name })

describe('SourcesPage (real client against the contract paths)', () => {
  it('lists sources with their health', async () => {
    setup()
    const acme = await row('Acme Careers')
    expect(within(acme).getByTestId('state-1')).toHaveTextContent('Healthy')
    expect(acme).toHaveTextContent('12 active, 1 stale postings')
    expect(acme).toHaveTextContent('Last run: 2 added, 1 updated, 0 closed')
    const globex = await row('Globex Jobs')
    expect(within(globex).getByTestId('state-2')).toHaveTextContent('Failing')
    expect(globex).toHaveTextContent('fetch_failed')
  })

  it('adds a source with a default name and shows it as not crawled yet', async () => {
    setup()
    await row('Acme Careers')
    await userEvent.type(screen.getByLabelText('Career page URL'), 'https://initech.example/careers/?utm_source=x')
    await userEvent.click(screen.getByRole('button', { name: 'Add source' }))
    const added = await row('initech.example')
    expect(added).toHaveTextContent('https://initech.example/careers')
    expect(added).toHaveTextContent('Not crawled yet')
    expect(added).toHaveTextContent('Never crawled')
    expect(screen.getByLabelText('Career page URL')).toHaveValue('')
  })

  it('shows a duplicate as an error and a refused host as the server detail', async () => {
    setup()
    await row('Acme Careers')
    const url = screen.getByLabelText('Career page URL')
    await userEvent.type(url, 'https://ACME.example/careers/')
    await userEvent.click(screen.getByRole('button', { name: 'Add source' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('You already have this source.')
    await userEvent.clear(url)
    await userEvent.type(url, 'http://localhost/jobs')
    await userEvent.click(screen.getByRole('button', { name: 'Add source' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('public host')
  })

  it('renames a source', async () => {
    setup()
    await row('Acme Careers')
    await userEvent.click(screen.getByRole('button', { name: 'Rename Acme Careers' }))
    const input = screen.getByLabelText('New name for Acme Careers')
    await userEvent.clear(input)
    await userEvent.type(input, 'Acme Corp')
    await userEvent.click(screen.getByRole('button', { name: 'Save' }))
    expect(await row('Acme Corp')).toBeInTheDocument()
  })

  it('disables and re-enables a source', async () => {
    const sources = setup()
    await row('Acme Careers')
    const box = screen.getByLabelText('Enabled: Acme Careers')
    expect(box).toBeChecked()
    await userEvent.click(box)
    expect((await sources.list())[0].enabled).toBe(false)
    expect(await screen.findByLabelText('Enabled: Acme Careers')).not.toBeChecked()
  })

  it('deletes only after confirmation', async () => {
    setup()
    await row('Globex Jobs')
    await userEvent.click(screen.getByRole('button', { name: 'Delete Globex Jobs' }))
    expect(screen.getByRole('listitem', { name: 'Globex Jobs' })).toBeInTheDocument()
    expect(screen.getByText('Postings already found stay in the feed.')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Confirm delete Globex Jobs' }))
    await vi.waitFor(() => expect(screen.queryByRole('listitem', { name: 'Globex Jobs' })).not.toBeInTheDocument())
  })

  it('shows an empty state and a load failure', async () => {
    const sources = createFakeSources()
    for (const s of await sources.list()) await sources.remove(s.id)
    setup(sources)
    expect(await screen.findByText(/No sources yet/)).toBeInTheDocument()
  })

  it('shows a message when loading fails', async () => {
    const sources = createFakeSources()
    sources.health = () => Promise.reject(new Error('x'))
    setup(sources)
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load sources.')
  })
})
