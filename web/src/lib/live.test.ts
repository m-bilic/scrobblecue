import { describe, expect, it } from 'vitest'
import type { PreparedTracklist } from '../api/types'
import type { QueuedScrobble } from '../store/db'
import { currentTrack, nextChange, nextSide, onLastTrack, playedEnough, sideFinished, type LiveSession, type LiveTrack } from './live'
import { isDue } from './scrobbler'

function track(position: string, startsAt: string, endsAt: string, scrobble = true): LiveTrack {
  return { position, title: position, artist: 'Artist', durationSec: 0, startsAt, endsAt, scrobble }
}

const tracklist = {
  releaseId: 1,
  album: 'Album',
  albumArtist: 'Artist',
  sideScrobblable: true,
  groups: [
    { kind: 'SIDE', label: 'A', tracks: [] },
    { kind: 'SIDE', label: 'B', tracks: [] },
  ],
  unassigned: [],
} as PreparedTracklist

const session: LiveSession = {
  playId: 'p1',
  tracklist,
  cover: '',
  side: 'A',
  tracks: [
    track('A1', '2026-10-28T20:00:00Z', '2026-10-28T20:05:00Z'),
    track('A2', '2026-10-28T20:05:00Z', '2026-10-28T20:05:20Z', false),
    track('A3', '2026-10-28T20:05:20Z', '2026-10-28T20:10:00Z'),
  ],
}

const at = (iso: string) => Date.parse(iso)

describe('live session', () => {
  it('knows which track is playing', () => {
    expect(currentTrack(session, at('2026-10-28T20:04:59Z'))?.position).toBe('A1')
    expect(currentTrack(session, at('2026-10-28T20:05:00Z'))?.position).toBe('A2')
    expect(currentTrack(session, at('2026-10-28T20:10:00Z'))).toBeUndefined()
  })

  it('plans the next run for when a track ends', () => {
    expect(nextChange(session, at('2026-10-28T20:01:00Z'))).toBe(at('2026-10-28T20:05:00Z'))
    expect(nextChange(session, at('2026-10-28T20:10:00Z'))).toBeUndefined()
  })

  it('counts a stopped track once half of it or 4 minutes have played', () => {
    const short = { ...track('A1', '2026-10-28T20:00:00Z', '2026-10-28T20:05:00Z'), durationSec: 300 }
    expect(playedEnough(short, at('2026-10-28T20:02:29Z'))).toBe(false)
    expect(playedEnough(short, at('2026-10-28T20:02:30Z'))).toBe(true)

    const long = { ...track('A1', '2026-10-28T20:00:00Z', '2026-10-28T20:12:00Z'), durationSec: 720 }
    expect(playedEnough(long, at('2026-10-28T20:04:00Z'))).toBe(true)
  })

  it('allows flipping during the last track', () => {
    expect(onLastTrack(session, at('2026-10-28T20:05:10Z'))).toBe(false)
    expect(onLastTrack(session, at('2026-10-28T20:09:00Z'))).toBe(true)
  })

  it('offers the next side when the side has finished', () => {
    expect(sideFinished(session, at('2026-10-28T20:09:59Z'))).toBe(false)
    expect(sideFinished(session, at('2026-10-28T20:10:00Z'))).toBe(true)
    expect(nextSide(session)?.label).toBe('B')
    expect(nextSide({ ...session, side: 'B' })).toBeUndefined()
  })
})

describe('isDue', () => {
  const queued = (sendAfter?: string) => ({ sendAfter }) as QueuedScrobble

  it('sends instant scrobbles at once and live ones only after their track has ended', () => {
    const due = isDue(at('2026-10-28T20:05:00Z'))
    expect(due(queued())).toBe(true)
    expect(due(queued('2026-10-28T20:05:00Z'))).toBe(true)
    expect(due(queued('2026-10-28T20:05:01Z'))).toBe(false)
  })
})
