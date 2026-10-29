import { api, ApiError, unwrap, type Problem } from '../api/client'
import type { PreparedTrack, PreparedTracklist, RecentScrobble, TimelineEntry } from '../api/types'
import { db, type Play, type PlayScope, type QueuedScrobble } from '../store/db'
import { isoSeconds } from './time'

/** Last.fm accepts at most 50 scrobbles per request. */
const BATCH = 50

export interface ScrobbleInput {
  tracklist: PreparedTracklist
  cover: string
  scope: PlayScope
  side?: string
  /** Tracks in play order. */
  tracks: PreparedTrack[]
  endedAt: Date
}

/**
 * Instant mode: computes timestamps (the last track ends at `endedAt`), writes the play and its
 * scrobbles to the local queue, then sends the queue. Nothing is lost if sending fails: the queue is
 * retried later.
 */
export async function scrobblePlay(input: ScrobbleInput): Promise<Play> {
  const timeline = unwrap(
    await api.POST('/v1/scrobbles/timeline', {
      body: {
        endedAt: isoSeconds(input.endedAt),
        tracks: input.tracks.map((t) => ({ position: t.position, durationSec: t.durationSec, scrobblable: t.scrobblable })),
      },
    }),
  ).tracks as TimelineEntry[]

  const play: Play = {
    id: crypto.randomUUID(),
    releaseId: input.tracklist.releaseId,
    album: input.tracklist.album,
    albumArtist: input.tracklist.albumArtist,
    cover: input.cover,
    scope: input.scope,
    mode: 'INSTANT',
    side: input.side,
    endedAt: isoSeconds(input.endedAt),
    createdAt: new Date().toISOString(),
    status: 'sending',
    accepted: 0,
    ignored: 0,
  }
  const queued: QueuedScrobble[] = []
  timeline.forEach((entry, i) => {
    const track = input.tracks[i]
    if (!entry.scrobble) return
    queued.push({
      id: crypto.randomUUID(),
      playId: play.id,
      seq: i,
      artist: track.artist,
      track: track.title,
      album: input.tracklist.album,
      // Last.fm wants albumArtist only when it differs from the track artist (compilations)
      albumArtist: input.tracklist.albumArtist !== track.artist ? input.tracklist.albumArtist : undefined,
      durationSec: track.durationSec,
      timestamp: entry.startsAt,
      state: 'pending',
    })
  })
  if (queued.length === 0) {
    play.status = 'done'
    play.message = 'No tracks long enough to scrobble.'
  }

  await db.transaction('rw', db.plays, db.queue, async () => {
    await db.plays.add(play)
    await db.queue.bulkAdd(queued)
  })
  await flushQueue()
  return (await db.plays.get(play.id)) ?? play
}

let flushing: Promise<void> | undefined

/** Sends everything in the local queue. Safe to call often; concurrent calls share one run. */
export function flushQueue(): Promise<void> {
  flushing ??= doFlush().finally(() => (flushing = undefined))
  return flushing
}

async function doFlush(): Promise<void> {
  const due = isDue(Date.now())
  const all = (await db.queue.toArray()).filter(due)
  const byPlay = Map.groupBy(all, (q) => q.playId)
  for (const [playId, items] of byPlay) {
    const stop = await sendPlay(playId, items.sort((a, b) => a.seq - b.seq))
    if (stop) return
  }
}

/** @returns true when sending should stop for now (offline, signed out, rate limited). */
async function sendPlay(playId: string, items: QueuedScrobble[]): Promise<boolean> {
  let remaining = items
  if (remaining.some((q) => q.state === 'uncertain')) {
    try {
      remaining = await withoutAlreadyScrobbled(remaining)
    } catch {
      return true
    }
  }

  for (let i = 0; i < remaining.length; i += BATCH) {
    const batch = remaining.slice(i, i + BATCH)
    let response: Response
    let data: { results?: { status?: string }[] } | undefined
    let error: unknown
    try {
      const result = await api.POST('/v1/lastfm/scrobbles', {
        body: {
          scrobbles: batch.map((q) => ({
            artist: q.artist,
            track: q.track,
            album: q.album,
            albumArtist: q.albumArtist,
            durationSec: q.durationSec,
            timestamp: q.timestamp,
          })),
        },
      })
      response = result.response
      data = result.data
      error = result.error
    } catch {
      // No answer at all (offline, timeout): the scrobbles may or may not have reached Last.fm
      await markUncertain(batch, playId, 'Offline – will retry when the connection is back.')
      return true
    }

    if (response.ok && data?.results) {
      const accepted = data.results.filter((r) => r.status === 'ACCEPTED').length
      await db.transaction('rw', db.plays, db.queue, async () => {
        await db.queue.bulkDelete(batch.map((q) => q.id))
        const play = await db.plays.get(playId)
        if (play) {
          const left = await db.queue.where('playId').equals(playId).count()
          await db.plays.update(playId, {
            accepted: play.accepted + accepted,
            ignored: play.ignored + (data!.results!.length - accepted),
            status: left === 0 ? 'done' : 'sending',
            message: undefined,
          })
        }
      })
      continue
    }

    const apiError = new ApiError(response.status, error as Problem | undefined)
    if (response.status === 502) {
      // Last.fm's answer was unreadable: it may have accepted the scrobbles
      await markUncertain(batch, playId, apiError.message)
    } else {
      await db.plays.update(playId, { status: 'waiting', message: describe(apiError) })
    }
    return true
  }
  return false
}

async function markUncertain(batch: QueuedScrobble[], playId: string, message: string) {
  await db.transaction('rw', db.plays, db.queue, async () => {
    await db.queue.bulkUpdate(batch.map((q) => ({ key: q.id, changes: { state: 'uncertain' as const } })))
    await db.plays.update(playId, { status: 'waiting', message })
  })
}

/** Removes queued scrobbles that Last.fm already has (same track, same timestamp). */
async function withoutAlreadyScrobbled(items: QueuedScrobble[]): Promise<QueuedScrobble[]> {
  const times = items.map((q) => Date.parse(q.timestamp))
  const from = isoSeconds(new Date(Math.min(...times) - 1000))
  const to = isoSeconds(new Date(Math.max(...times) + 1000))
  const recent = unwrap(await api.GET('/v1/lastfm/recent', { params: { query: { from, to } } }))
    .scrobbles as RecentScrobble[]
  const done = alreadyScrobbled(items, recent)
  if (done.length > 0) {
    await db.queue.bulkDelete(done.map((q) => q.id))
  }
  const doneIds = new Set(done.map((q) => q.id))
  return items.filter((q) => !doneIds.has(q.id))
}

/** Live-mode scrobbles wait until their track has ended; everything else can be sent right away. */
export function isDue(now: number): (q: QueuedScrobble) => boolean {
  return (q) => q.sendAfter === undefined || Date.parse(q.sendAfter) <= now
}

/** Queued scrobbles found among the user's recent Last.fm scrobbles. */
export function alreadyScrobbled(items: QueuedScrobble[], recent: RecentScrobble[]): QueuedScrobble[] {
  const key = (track: string, timestamp: string) => `${track.trim().toLowerCase()}|${Math.floor(Date.parse(timestamp) / 1000)}`
  const seen = new Set(recent.map((r) => key(r.track, r.timestamp)))
  return items.filter((q) => seen.has(key(q.track, q.timestamp)))
}

function describe(error: ApiError): string {
  switch (error.code) {
    case 'lastfm-session-revoked':
    case 'credentials-missing':
    case 'credentials-invalid':
      return 'Last.fm needs to be connected again. Your scrobbles are kept and sent afterwards.'
    case 'lastfm-unavailable':
    case 'lastfm-rate-limited':
      return 'Last.fm is busy right now. Your scrobbles are kept and sent later.'
    default:
      return error.message
  }
}
