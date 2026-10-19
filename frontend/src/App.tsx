import { Link, Route, Routes } from 'react-router-dom'

function Page({ title }: { title: string }) {
  return <h1>{title}</h1>
}

export default function App() {
  return (
    <>
      <nav>
        <Link to="/">Home</Link> <Link to="/login">Login</Link>{' '}
        <Link to="/resume">Master resume</Link> <Link to="/jobs">Jobs</Link>
      </nav>
      <Routes>
        <Route path="/" element={<Page title="Resume Editor" />} />
        <Route path="/login" element={<Page title="Login" />} />
        <Route path="/resume" element={<Page title="Master resume" />} />
        <Route path="/jobs" element={<Page title="Jobs" />} />
        <Route path="/drafts/:id" element={<Page title="Draft" />} />
        <Route path="*" element={<Page title="Not found" />} />
      </Routes>
    </>
  )
}
