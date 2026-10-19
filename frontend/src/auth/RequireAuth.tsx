import { Navigate, Outlet, useLocation } from 'react-router-dom'
import { useAuth } from './AuthContext'

/** Route guard: signed-out users go to /login and return to where they were after signing in. */
export default function RequireAuth() {
  const { user } = useAuth()
  const location = useLocation()
  if (user === undefined) return <p>Loading...</p>
  if (user === null) return <Navigate to="/login" replace state={{ from: location.pathname }} />
  return <Outlet />
}
