import { useState } from 'react'
import { currentTrack, endLive, nextSide, onLastTrack, sideFinished, startLive, stopLive, useLiveSession } from '../lib/live'
import { formatDuration } from '../lib/time'

/** Shown at the bottom of every page while a side plays live: the current track, Stop, and the flip prompt. */
export function LiveBar() {
  const { session, now } = useLiveSession()
  const [error, setError] = useState<string>()
  if (!session) return null

  const run = (action: () => Promise<unknown>) => {
    setError(undefined)
    action().catch((e: Error) => setError(e.message))
  }

  const sideStart = Date.parse(session.tracks[0]?.startsAt ?? '')
  const sideEnd = Date.parse(session.tracks.at(-1)?.endsAt ?? '')
  const progress = Math.min(1, Math.max(0, (now - sideStart) / (sideEnd - sideStart || 1)))

  if (sideFinished(session, now)) {
    const next = nextSide(session)
    return (
      <div className="live-bar" role="status">
        <div className="live-text">
          <strong>Side {session.side} is done.</strong> {next ? `Flip to side ${next.label}?` : 'That was the last side.'}
          {error && <span className="error"> {error}</span>}
        </div>
        {next && (
          <button type="button" className="primary" onClick={() => run(() => startLive(session.tracklist, next, session.cover))}>
            Start side {next.label}
          </button>
        )}
        <button type="button" onClick={() => run(endLive)}>
          Done
        </button>
      </div>
    )
  }

  const track = currentTrack(session, now)
  // The record may be a little ahead of the estimated lengths: allow flipping during the last track
  const next = onLastTrack(session, now) ? nextSide(session) : undefined
  const left = track ? Math.max(0, Math.round((Date.parse(track.endsAt) - now) / 1000)) : 0
  return (
    <div className="live-bar" role="status">
      <div className="live-progress" style={{ width: `${progress * 100}%` }} />
      <div className="live-text">
        <span className="muted">
          {session.tracklist.album} · Side {session.side}
        </span>
        {track && (
          <span>
            <strong>
              {track.position} {track.title}
            </strong>{' '}
            <span className="muted">{formatDuration(left)} left</span>
            {!track.scrobble && <span className="muted"> (not scrobbled)</span>}
          </span>
        )}
        {error && <span className="error">{error}</span>}
      </div>
      {next && (
        <button type="button" className="primary" onClick={() => run(() => startLive(session.tracklist, next, session.cover))}>
          Start side {next.label}
        </button>
      )}
      <button type="button" onClick={() => run(stopLive)}>
        Stop
      </button>
    </div>
  )
}
