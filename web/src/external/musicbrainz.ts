import { db } from '../store/db'

const MB = 'https://musicbrainz.org/ws/2'

/** MusicBrainz allows one request per second per IP. */
const MIN_INTERVAL_MS = 1100

let lastRequest = 0

async function mbFetch(path: string): Promise<Response> {
  const wait = lastRequest + MIN_INTERVAL_MS - Date.now()
  if (wait > 0) await new Promise((resolve) => setTimeout(resolve, wait))
  lastRequest = Date.now()
  return fetch(`${MB}${path}${path.includes('?') ? '&' : '?'}fmt=json`)
}

export interface MbTrack {
  number: string
  length: number | null
}

/**
 * Matches MusicBrainz tracks to Discogs positions. MusicBrainz track numbers for vinyl usually are the
 * Discogs positions ("A", "B1"); if not, tracks are matched by order when the counts agree.
 */
export function matchDurations(discogsPositions: string[], mbTracks: MbTrack[]): Record<string, number> {
  const durations: Record<string, number> = {}
  const byNumber = new Map(mbTracks.map((t) => [t.number.trim().toUpperCase(), t]))
  const allMatchByNumber = discogsPositions.every((p) => byNumber.has(p.toUpperCase()))
  discogsPositions.forEach((position, i) => {
    const track = allMatchByNumber
      ? byNumber.get(position.toUpperCase())
      : mbTracks.length === discogsPositions.length
        ? mbTracks[i]
        : undefined
    if (track?.length) durations[position] = Math.round(track.length / 1000)
  })
  return durations
}

/**
 * Looks up missing track lengths on MusicBrainz via its link to the Discogs release. The result
 * (also an empty one) is cached, so each release is looked up at most once.
 *
 * @param allPositions every track position of the Discogs release, in order
 */
export async function lookupDurations(releaseId: number, allPositions: string[]): Promise<Record<string, number>> {
  const cached = await db.lookups.get(releaseId)
  if (cached) return cached.durations

  let durations: Record<string, number> = {}
  try {
    const resource = encodeURIComponent(`https://www.discogs.com/release/${releaseId}`)
    const urlResponse = await mbFetch(`/url?resource=${resource}&inc=release-rels`)
    if (urlResponse.ok) {
      const url = (await urlResponse.json()) as { relations?: { release?: { id: string } }[] }
      const mbid = url.relations?.find((r) => r.release)?.release?.id
      if (mbid) {
        const releaseResponse = await mbFetch(`/release/${mbid}?inc=recordings`)
        if (releaseResponse.ok) {
          const release = (await releaseResponse.json()) as { media?: { tracks?: MbTrack[] }[] }
          const tracks = (release.media ?? []).flatMap((m) => m.tracks ?? [])
          durations = matchDurations(allPositions, tracks)
        }
      }
    }
  } catch {
    // MusicBrainz unreachable: fall back to defaults this time, try again next visit
    return {}
  }
  await db.lookups.put({ releaseId, durations, checkedAt: new Date().toISOString() })
  return durations
}
