import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import type { Api, Credentials, User } from '../api/types'

interface AuthState {
  /** undefined while the initial /api/me check is running. */
  user: User | null | undefined
  logIn(c: Credentials): Promise<void>
  signUp(c: Credentials): Promise<void>
  logOut(): Promise<void>
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ api, children }: { api: Api; children: ReactNode }) {
  const [user, setUser] = useState<User | null | undefined>(undefined)

  useEffect(() => {
    let cancelled = false
    api.auth
      .getMe()
      .then((u) => !cancelled && setUser(u))
      .catch(() => !cancelled && setUser(null))
    return () => {
      cancelled = true
    }
  }, [api])

  const logIn = useCallback(async (c: Credentials) => setUser(await api.auth.logIn(c)), [api])
  const signUp = useCallback(async (c: Credentials) => setUser(await api.auth.signUp(c)), [api])
  const logOut = useCallback(async () => {
    await api.auth.logOut()
    setUser(null)
  }, [api])

  const value = useMemo(() => ({ user, logIn, signUp, logOut }), [user, logIn, signUp, logOut])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthState {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used inside AuthProvider')
  return ctx
}
