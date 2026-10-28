import { useState } from 'react'
import { useSearchParams } from 'react-router'
import type { Me } from '../api/types'
import { connect } from '../hooks/useSession'

const AUTH_ERRORS: Record<string, string> = {
  'lastfm-denied': 'Last.fm access was not allowed.',
  'lastfm-expired': 'Connecting Last.fm took too long. Please try again.',
  'lastfm-failed': 'Connecting Last.fm failed. Please try again.',
  'discogs-denied': 'Discogs access was not allowed.',
  'discogs-expired': 'Connecting Discogs took too long. Please try again.',
  'discogs-failed': 'Connecting Discogs failed. Please try again.',
}

export function ConnectPage({ me }: { me: Me | null }) {
  const [params] = useSearchParams()
  const [error, setError] = useState<string>()
  const authError = params.get('authError')

  async function start(provider: 'lastfm' | 'discogs') {
    setError(undefined)
    try {
      await connect(provider)
    } catch (e) {
      setError((e as Error).message)
    }
  }

  return (
    <section className="connect">
      <h1>Scrobble your records</h1>
      <p className="lead">
        Pick a record from your Discogs collection, tap a side, and it's on your Last.fm. No account, and nothing is stored on
        our server.
      </p>
      {(authError || error) && <p className="error">{AUTH_ERRORS[authError ?? ''] ?? error ?? authError}</p>}

      <ol className="steps">
        <li className={me ? 'done' : ''}>
          <h2>1. Connect Last.fm</h2>
          {me ? (
            <p>Connected as {me.lastfmUser}.</p>
          ) : (
            <button type="button" className="primary" onClick={() => start('lastfm')}>
              Connect Last.fm
            </button>
          )}
        </li>
        <li className={me?.discogsUser ? 'done' : ''}>
          <h2>2. Connect Discogs</h2>
          <p className="muted">ScrobbleCue only reads your collection.</p>
          <button type="button" className="primary" disabled={!me} onClick={() => start('discogs')}>
            Connect Discogs
          </button>
        </li>
      </ol>
    </section>
  )
}
