import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import App from './App'
import { AuthProvider } from './auth/AuthContext'
import { createDummyApi } from './dummy/dummyApi'
import type { Api } from './api/types'

function renderAt(path: string, api: Api = createDummyApi()) {
  render(
    <MemoryRouter initialEntries={[path]}>
      <AuthProvider api={api}>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
  return api
}

describe('route guard', () => {
  it('redirects a signed-out user to the login page', async () => {
    renderAt('/jobs')
    expect(await screen.findByRole('heading', { name: 'Log in' })).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Jobs' })).not.toBeInTheDocument()
  })

  it('lets a signed-in user through', async () => {
    const api = createDummyApi()
    await api.auth.signUp({ username: 'ada', password: 'correct-horse' })
    renderAt('/jobs', api)
    expect(await screen.findByRole('heading', { name: 'Jobs' })).toBeInTheDocument()
  })
})

describe('login form', () => {
  it('logs in and returns to the page the guard intercepted', async () => {
    const api = createDummyApi()
    await api.auth.signUp({ username: 'ada', password: 'correct-horse' })
    await api.auth.logOut()
    renderAt('/jobs', api)
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Username'), 'ada')
    await user.type(screen.getByLabelText('Password'), 'correct-horse')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByRole('heading', { name: 'Jobs' })).toBeInTheDocument()
  })

  it('shows the API error on bad credentials', async () => {
    renderAt('/login')
    const user = userEvent.setup()
    await user.type(await screen.findByLabelText('Username'), 'nobody')
    await user.type(screen.getByLabelText('Password'), 'wrong-password')
    await user.click(screen.getByRole('button', { name: 'Log in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Wrong username or password')
  })

  it('signs up a new account', async () => {
    renderAt('/login')
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Sign up/ }))
    await user.type(screen.getByLabelText('Username'), 'grace')
    await user.type(screen.getByLabelText('Password'), 'correct-horse')
    await user.click(screen.getByRole('button', { name: 'Create account' }))
    expect(await screen.findByRole('heading', { name: 'Resume Editor' })).toBeInTheDocument()
  })
})
