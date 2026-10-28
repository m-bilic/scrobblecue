import { Navigate, Route, Routes } from 'react-router'
import { Footer } from './components/Footer'
import { Header } from './components/Header'
import { useSession } from './hooks/useSession'
import { CollectionPage } from './pages/CollectionPage'
import { ConnectPage } from './pages/ConnectPage'
import { RecordPage } from './pages/RecordPage'

export function App() {
  const session = useSession()
  const ready = session.data?.lastfmUser && session.data.discogsUser

  return (
    <div className="app">
      <Header me={session.data ?? null} />
      <main>
        {session.isPending ? (
          <p className="muted">Loading…</p>
        ) : session.isError ? (
          <p className="error">The ScrobbleCue server can't be reached right now. Try again in a moment.</p>
        ) : (
          <Routes>
            <Route path="/" element={ready ? <CollectionPage /> : <ConnectPage me={session.data ?? null} />} />
            <Route path="/record/:releaseId" element={ready ? <RecordPage /> : <Navigate to="/" replace />} />
            <Route path="*" element={<Navigate to="/" replace />} />
          </Routes>
        )}
      </main>
      <Footer />
    </div>
  )
}
