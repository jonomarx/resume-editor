import { Link, Route, Routes } from 'react-router-dom'
import RequireAuth from './auth/RequireAuth'
import { useAuth } from './auth/AuthContext'
import LoginPage from './pages/LoginPage'
import SourcesPage from './sources/SourcesPage'
import MasterResumePage from './pages/MasterResumePage'

function Page({ title }: { title: string }) {
  return <h1>{title}</h1>
}

export default function App() {
  const { user, logOut } = useAuth()
  return (
    <>
      <nav>
        <Link to="/">Home</Link> <Link to="/resume">Master resume</Link> <Link to="/jobs">Jobs</Link> <Link to="/sources">Sources</Link>{' '}
        {user ? <button onClick={() => void logOut()}>Log out ({user.username})</button> : <Link to="/login">Login</Link>}
      </nav>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route element={<RequireAuth />}>
          <Route path="/" element={<Page title="Resume Editor" />} />
          <Route path="/resume" element={<MasterResumePage />} />
          <Route path="/sources" element={<SourcesPage />} />
          <Route path="/jobs" element={<Page title="Jobs" />} />
          <Route path="/drafts/:id" element={<Page title="Draft" />} />
        </Route>
        <Route path="*" element={<Page title="Not found" />} />
      </Routes>
    </>
  )
}
