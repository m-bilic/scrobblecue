import { useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router'
import { api } from '../api/client'
import type { Me } from '../api/types'
import { db } from '../store/db'

export function Header({ me }: { me: Me | null }) {
  const queryClient = useQueryClient()

  async function logOut() {
    await api.POST('/v1/auth/logout')
    queryClient.setQueryData(['me'], null)
  }

  async function clearData() {
    if (!confirm('Delete everything ScrobbleCue stored in this browser (collection, corrections, play log, unsent scrobbles) and log out?')) return
    await logOut()
    await db.delete()
    window.location.assign('/')
  }

  return (
    <header className="header">
      <Link to="/" className="brand">
        <span className="brand-disc" aria-hidden="true" />
        ScrobbleCue
      </Link>
      {me && (
        <nav className="account">
          <a href={`https://www.last.fm/user/${encodeURIComponent(me.lastfmUser)}`} target="_blank" rel="noreferrer">
            {me.lastfmUser}
          </a>
          <button type="button" className="link" onClick={logOut}>
            Log out
          </button>
          <button type="button" className="link" onClick={clearData}>
            Clear data
          </button>
        </nav>
      )}
    </header>
  )
}
