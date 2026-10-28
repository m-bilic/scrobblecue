import { describe, expect, it } from 'vitest'
import { matchDurations } from './musicbrainz'

describe('matchDurations', () => {
  it('matches by track number when MusicBrainz uses vinyl positions', () => {
    // Real data: MusicBrainz release for Discogs release 1 (The Persuader – Stockholm)
    const mb = [
      { number: 'A', length: 285000 },
      { number: 'B1', length: 371000 },
      { number: 'B2', length: 169000 },
      { number: 'C1', length: 338000 },
      { number: 'C2', length: 292000 },
      { number: 'D', length: 316000 },
    ]

    expect(matchDurations(['A', 'B1', 'B2', 'C1', 'C2', 'D'], mb)).toEqual({
      A: 285,
      B1: 371,
      B2: 169,
      C1: 338,
      C2: 292,
      D: 316,
    })
  })

  it('falls back to track order when numbers differ but counts match', () => {
    const mb = [
      { number: '1', length: 200400 },
      { number: '2', length: null },
    ]

    expect(matchDurations(['A1', 'B1'], mb)).toEqual({ A1: 200 })
  })

  it('gives up when neither numbers nor counts match', () => {
    expect(matchDurations(['A1', 'A2', 'B1'], [{ number: '1', length: 1000 }])).toEqual({})
  })
})
