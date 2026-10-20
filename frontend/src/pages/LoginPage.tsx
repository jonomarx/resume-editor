import { useState, type FormEvent } from 'react'
import { Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError } from '../api/types'
import { AUTH, PASSWORD_MESSAGES } from '../config/constants'
import { signUpPasswordProblem } from '../auth/passwordRules'
import { useAuth } from '../auth/AuthContext'

export default function LoginPage() {
  const { user, logIn, signUp } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const from = (location.state as { from?: string } | null)?.from ?? '/'
  const [mode, setMode] = useState<'login' | 'signup'>('login')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [serverPasswordError, setServerPasswordError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const localProblem = mode === 'signup' ? signUpPasswordProblem(password) : null
  const passwordError = localProblem ?? serverPasswordError

  if (user) return <Navigate to={from} replace />

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (localProblem) return
    setError(null)
    setServerPasswordError(null)
    setBusy(true)
    try {
      await (mode === 'login' ? logIn : signUp)({ username, password })
      navigate(from, { replace: true })
    } catch (err) {
      const detail = err instanceof ApiError && mode === 'signup' && err.status === 400 ? err.details?.find((d) => d.field === 'password') : undefined
      if (detail) setServerPasswordError(detail.message || PASSWORD_MESSAGES.rejected)
      else setError(err instanceof ApiError ? err.message : 'Something went wrong. Try again.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <form onSubmit={submit}>
      <h1>{mode === 'login' ? 'Log in' : 'Sign up'}</h1>
      <label>
        Username
        <input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" required pattern={AUTH.usernamePattern} />
      </label>
      <label>
        Password
        <input type="password" value={password} onChange={(e) => { setPassword(e.target.value); setServerPasswordError(null) }} aria-invalid={passwordError ? true : undefined} aria-describedby={passwordError ? 'password-error' : undefined} autoComplete={mode === 'login' ? 'current-password' : 'new-password'} required minLength={AUTH.passwordMinLength} />
      </label>
      {passwordError && <p id="password-error">{passwordError}</p>}
      {error && <p role="alert">{error}</p>}
      <button type="submit" disabled={busy || !!localProblem}>{mode === 'login' ? 'Log in' : 'Create account'}</button>
      <button type="button" onClick={() => { setMode(mode === 'login' ? 'signup' : 'login'); setError(null); setServerPasswordError(null) }}>
        {mode === 'login' ? 'Need an account? Sign up' : 'Have an account? Log in'}
      </button>
    </form>
  )
}
