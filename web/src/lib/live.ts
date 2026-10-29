import { useEffect, useState } from 'react'
import { api, unwrap } from '../api/client'
import type { PreparedTrack, PreparedTracklist, TimelineEntry, TrackGroup } from '../api/types'
import { db, getMeta, setMeta, type Play, type QueuedScrobble } from '../store/db'
import { flushQueue } from './scrobbler'
import { isoSeconds } from './time'

/**
 * Live mode (SPEC.md flow 8.6): the user starts a side as the needle drops; each track is scrobbled
 * when it has finished playing, and Last.fm shows it as "now playing" meanwhile.
 *
 * All scrobbles are queued at the start, each with `sendAfter` = the end of its track, so nothing is
 * lost when the tab is closed: the regular queue sends whatever is due on the next visit. Timers here
 * only make it happen on time while the tab is open, and keep "now playing" current.
 */

export interface LiveTrack {
  position: string
  title: string
  artist: string
  durationSec: number
  startsAt: string
  endsAt: string
  scrobble: boolean
}

/** The side being played, kept in the local database so a reload continues where it was. */
export interface LiveSession {
  playId: string
  tracklist: PreparedTracklist
  cover: string
  side: string
  tracks: LiveTrack[]
}

const SESSION_KEY = 'liveSession'

let session: LiveSession | null = null
let timer: ReturnType<typeof setTimeout> | undefined
let nowPlayingSent: string | undefined
const listeners = new Set<(s: LiveSession | null) => void>()

/** Starts playing [group] live; a side already playing is stopped first. */
export async function startLive(tracklist: PreparedTracklist, group: TrackGroup, cover: string): Promise<LiveSession> {
  await stopLive()
  const startedAt = new Date()
  const timeline = unwrap(
    await api.POST('/v1/scrobbles/timeline', {
      body: {
        startedAt: isoSeconds(startedAt),
        tracks: group.tracks.map((t) => ({ position: t.position, durationSec: t.durationSec, scrobblable: t.scrobblable })),
      },
    }),
  ).tracks as TimelineEntry[]

  const tracks: LiveTrack[] = timeline.map((entry, i) => {
    const track: PreparedTrack = group.tracks[i]
    return {
      position: track.position,
      title: track.title,
      artist: track.artist,
      durationSec: track.durationSec,
      startsAt: entry.startsAt,
      endsAt: entry.endsAt,
      scrobble: entry.scrobble,
    }
  })
  const play: Play = {
    id: crypto.randomUUID(),
    releaseId: tracklist.releaseId,
    album: tracklist.album,
    albumArtist: tracklist.albumArtist,
    cover,
    scope: 'SIDE',
    mode: 'LIVE',
    side: group.label,
    endedAt: tracks.at(-1)?.endsAt ?? isoSeconds(startedAt),
    createdAt: new Date().toISOString(),
    status: 'sending',
    accepted: 0,
    ignored: 0,
  }
  const queued: QueuedScrobble[] = tracks
    .map((t, i) => ({ t, i }))
    .filter(({ t }) => t.scrobble)
    .map(({ t, i }) => ({
      id: crypto.randomUUID(),
      playId: play.id,
      seq: i,
      artist: t.artist,
      track: t.title,
      album: tracklist.album,
      albumArtist: tracklist.albumArtist !== t.artist ? tracklist.albumArtist : undefined,
      durationSec: t.durationSec,
      timestamp: t.startsAt,
      sendAfter: t.endsAt,
      state: 'pending' as const,
    }))

  const started: LiveSession = { playId: play.id, tracklist, cover, side: group.label, tracks }
  await db.transaction('rw', db.plays, db.queue, db.meta, async () => {
    await db.plays.add(play)
    await db.queue.bulkAdd(queued)
    await setMeta(SESSION_KEY, JSON.stringify(started))
  })
  setSession(started)
  tick()
  return started
}

/**
 * Stops the side. Finished tracks are sent as usual, and so is the track playing now if enough of it has
 * played (Last.fm's rule; the record may run ahead of the estimated lengths). Others are dropped.
 */
export async function stopLive(): Promise<void> {
  const current = session
  if (!current) return
  const now = Date.now()
  const keep = new Set(current.tracks.filter((t) => Date.parse(t.endsAt) > now && playedEnough(t, now)).map((t) => t.startsAt))
  await db.transaction('rw', db.plays, db.queue, db.meta, async () => {
    const unfinished = await db.queue
      .where('playId')
      .equals(current.playId)
      .filter((q) => q.sendAfter !== undefined && Date.parse(q.sendAfter) > now)
      .toArray()
    const sendNow = unfinished.filter((q) => keep.has(q.timestamp))
    await db.queue.bulkUpdate(sendNow.map((q) => ({ key: q.id, changes: { sendAfter: isoSeconds(new Date(now)) } })))
    await db.queue.bulkDelete(unfinished.filter((q) => !keep.has(q.timestamp)).map((q) => q.id))
    if ((await db.queue.where('playId').equals(current.playId).count()) === 0) {
      await db.plays.update(current.playId, { status: 'done' })
    }
    await db.meta.delete(SESSION_KEY)
  })
  setSession(null)
  void flushQueue()
}

/** After a side has finished: the user is done, or starts the next side (see {@link nextSide}). */
export async function endLive(): Promise<void> {
  if (!session) return
  await db.meta.delete(SESSION_KEY)
  setSession(null)
}

/** Picks up a side that was playing when the page was closed or reloaded. */
export async function resumeLive(): Promise<void> {
  const stored = await getMeta(SESSION_KEY)
  if (!stored) return
  setSession(JSON.parse(stored) as LiveSession)
  tick()
}

/** The track playing at [now], if any. */
export function currentTrack(s: LiveSession, now: number): LiveTrack | undefined {
  return s.tracks.find((t) => Date.parse(t.startsAt) <= now && now < Date.parse(t.endsAt))
}

/**
 * Last.fm's rule for an interrupted track: it counts once half of it, or 4 minutes, has played. Used
 * when the user stops or flips early.
 */
export function playedEnough(track: LiveTrack, now: number): boolean {
  const played = (now - Date.parse(track.startsAt)) / 1000
  return played > 0 && played >= Math.min(Math.floor(track.durationSec / 2), 240)
}

/** The last track is playing: the user may flip a little early. */
export function onLastTrack(s: LiveSession, now: number): boolean {
  const last = s.tracks.at(-1)
  return last !== undefined && currentTrack(s, now) === last
}

/** The side has played to the end. */
export function sideFinished(s: LiveSession, now: number): boolean {
  const last = s.tracks.at(-1)
  return last === undefined || Date.parse(last.endsAt) <= now
}

/** The next moment something changes (a track ends or starts), in ms since the epoch. */
export function nextChange(s: LiveSession, now: number): number | undefined {
  return s.tracks
    .flatMap((t) => [Date.parse(t.startsAt), Date.parse(t.endsAt)])
    .filter((time) => time > now)
    .sort((a, b) => a - b)[0]
}

/** The side after the one being played (B after A), for "Flip to side B?". */
export function nextSide(s: LiveSession): TrackGroup | undefined {
  const sides = s.tracklist.groups.filter((g) => g.kind === 'SIDE')
  const index = sides.findIndex((g) => g.label === s.side)
  return index >= 0 ? sides[index + 1] : undefined
}

/** Sends what is due, keeps "now playing" current and plans the next run. */
function tick() {
  clearTimeout(timer)
  const current = session
  if (!current) return
  const now = Date.now()
  void flushQueue()

  const track = currentTrack(current, now)
  const key = track ? `${current.playId}|${track.position}` : undefined
  if (track && track.scrobble && key !== nowPlayingSent) {
    nowPlayingSent = key
    // Best effort: "now playing" is only a display on Last.fm
    void api
      .POST('/v1/lastfm/now-playing', {
        body: {
          artist: track.artist,
          track: track.title,
          album: current.tracklist.album,
          albumArtist: current.tracklist.albumArtist !== track.artist ? current.tracklist.albumArtist : undefined,
          durationSec: track.durationSec,
        },
      })
      .catch(() => undefined)
  }

  const next = nextChange(current, now)
  // +500 ms: run just after the track has ended, so its scrobble is due
  if (next !== undefined) timer = setTimeout(tick, next - now + 500)
  emit()
}

function setSession(s: LiveSession | null) {
  session = s
  if (!s) clearTimeout(timer)
  emit()
}

function emit() {
  listeners.forEach((listener) => listener(session))
}

/** The live session plus the current time, updated every second while a side is playing. */
export function useLiveSession(): { session: LiveSession | null; now: number } {
  const [current, setCurrent] = useState(session)
  const [now, setNow] = useState(Date.now())
  useEffect(() => {
    listeners.add(setCurrent)
    return () => void listeners.delete(setCurrent)
  }, [])
  useEffect(() => {
    if (!current) return
    const interval = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(interval)
  }, [current])
  return { session: current, now }
}
