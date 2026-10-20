import { fireEvent, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { createHttpApi } from '../api/http'
import { AuthProvider } from '../auth/AuthContext'
import { PASSWORD_MAX_BYTES, PASSWORD_MESSAGES } from '../config/constants'
import { createFakeServer, type FakeServer } from '../test/fakeServer'
import App from '../App'

let server: FakeServer

beforeEach(() => {
  server = createFakeServer()
  render(
    <MemoryRouter initialEntries={['/login']}>
      <AuthProvider api={createHttpApi(server.fetch)}>
        <App />
      </AuthProvider>
    </MemoryRouter>,
  )
})

async function signUpForm(password: string) {
  await userEvent.click(await screen.findByRole('button', { name: /Sign up/ }))
  await userEvent.type(screen.getByLabelText('Username'), 'ada')
  // fireEvent: userEvent.type would interpret braces and cannot type control characters
  fireEvent.change(screen.getByLabelText('Password'), { target: { value: password } })
}
const submit = () => screen.getByRole('button', { name: 'Create account' })

describe('sign-up password rules', () => {
  it('accepts exactly 72 bytes', async () => {
    await signUpForm('a'.repeat(PASSWORD_MAX_BYTES))
    expect(submit()).toBeEnabled()
    expect(screen.queryByText(PASSWORD_MESSAGES.tooLong)).not.toBeInTheDocument()
    await userEvent.click(submit())
    expect(await screen.findByRole('heading', { name: 'Resume Editor' })).toBeInTheDocument()
  })

  it('blocks 73 bytes with the message under the field', async () => {
    await signUpForm('a'.repeat(PASSWORD_MAX_BYTES + 1))
    expect(submit()).toBeDisabled()
    expect(screen.getByText(PASSWORD_MESSAGES.tooLong)).toBeInTheDocument()
    expect(screen.getByLabelText('Password')).toHaveAttribute('aria-describedby', 'password-error')
  })

  it('counts multi-byte characters as bytes, not characters', async () => {
    // 36 two-byte characters = 72 bytes (36 chars) accepted; 37 = 74 bytes blocked
    await signUpForm('é'.repeat(36))
    expect(submit()).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'é'.repeat(37) } })
    expect(submit()).toBeDisabled()
    // emoji are 4 bytes: 18 = 72 accepted, 19 = 76 blocked
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '😀'.repeat(18) } })
    expect(submit()).toBeEnabled()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: '😀'.repeat(19) } })
    expect(submit()).toBeDisabled()
  })

  it.each([['tab', 'abcdefg\thij'], ['U+001F', 'abcdefg\u001Fhij'], ['NUL', 'abcdefg\u0000hij'], ['DEL', 'abcdefg\u007Fhij']])(
    'blocks a %s control character',
    async (_n, pw) => {
      await signUpForm(pw)
      expect(submit()).toBeDisabled()
      expect(screen.getByText(PASSWORD_MESSAGES.controlCharacter)).toBeInTheDocument()
    },
  )

  it('shows a server 400 on password under the field and clears it when the password changes', async () => {
    server.rejectNextSignupPassword('is too common')
    await signUpForm('correct-horse')
    await userEvent.click(submit())
    expect(await screen.findByText('is too common')).toHaveAttribute('id', 'password-error')
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'another-horse' } })
    expect(screen.queryByText('is too common')).not.toBeInTheDocument()
  })
})

describe('login is unchanged', () => {
  it('a long or control-character password is not blocked client-side and gets the normal wrong-credentials message', async () => {
    await userEvent.type(await screen.findByLabelText('Username'), 'ada')
    fireEvent.change(screen.getByLabelText('Password'), { target: { value: 'a'.repeat(100) + '\t' } })
    const button = screen.getByRole('button', { name: 'Log in' })
    expect(button).toBeEnabled()
    expect(screen.queryByText(PASSWORD_MESSAGES.tooLong)).not.toBeInTheDocument()
    await userEvent.click(button)
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
  })
})
