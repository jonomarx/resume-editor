import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import App from './App'

describe('App', () => {
  it('renders the home route', () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: 'Resume Editor' })).toBeInTheDocument()
  })

  it('renders a placeholder route', () => {
    render(
      <MemoryRouter initialEntries={['/jobs']}>
        <App />
      </MemoryRouter>,
    )
    expect(screen.getByRole('heading', { name: 'Jobs' })).toBeInTheDocument()
  })
})
