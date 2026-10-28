import { describe, expect, it } from 'vitest'
import type { QueuedScrobble } from '../store/db'
import { alreadyScrobbled } from './scrobbler'

function queued(track: string, timestamp: string): QueuedScrobble {
  return {
    id: `${track}-${timestamp}`,
    playId: 'p1',
    seq: 0,
    artist: 'Boillat Therace Quintet',
    track,
    album: 'Boillat Thérace Quintet',
    durationSec: 300,
    timestamp,
    state: 'uncertain',
  }
}

describe('alreadyScrobbled', () => {
  it('finds queued scrobbles that Last.fm already has, by track and second', () => {
    const items = [queued('Sweet Fire', '2026-10-27T22:16:20Z'), queued('Cenovis', '2026-10-27T22:29:58Z')]
    const recent = [
      { artist: 'Boillat Therace Quintet', track: 'sweet fire ', album: '', timestamp: '2026-10-27T22:16:20Z' },
      // Same track, different time: a different play
      { artist: 'Boillat Therace Quintet', track: 'Cenovis', album: '', timestamp: '2026-10-26T10:00:00Z' },
    ]

    expect(alreadyScrobbled(items, recent).map((q) => q.track)).toEqual(['Sweet Fire'])
  })
})
