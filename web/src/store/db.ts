import Dexie, { type EntityTable } from 'dexie'

/**
 * Everything ScrobbleCue knows about the user lives here, in the browser (IndexedDB). The server
 * stores nothing. "Clear all data" deletes this database.
 */

export interface CollectionItem {
  instanceId: number
  releaseId: number
  title: string
  artist: string
  year: number
  formats: string[]
  isVinyl: boolean
  thumb: string
  cover: string
  addedAt: string
}

/** Raw Discogs release JSON, cached after first use. */
export interface CachedRelease {
  id: number
  json: unknown
  fetchedAt: string
}

/** Track lengths found outside Discogs (MusicBrainz), by Discogs position. */
export interface LookupDurations {
  releaseId: number
  durations: Record<string, number>
  checkedAt: string
}

export interface Correction {
  releaseId: number
  position: string
  title?: string
  artist?: string
  durationSec?: number
  skip: boolean
}

export type PlayScope = 'SIDE' | 'WHOLE' | 'TRACKS'

/** Instant: the record was already played. Live: tracks are scrobbled as they finish playing. */
export type PlayMode = 'INSTANT' | 'LIVE'

/** One listening session in the local play log. */
export interface Play {
  id: string
  releaseId: number
  album: string
  albumArtist: string
  cover: string
  scope: PlayScope
  /** Missing on plays saved before live mode existed (= instant). */
  mode?: PlayMode
  side?: string
  endedAt: string
  createdAt: string
  status: 'sending' | 'done' | 'waiting'
  accepted: number
  ignored: number
  message?: string
}

/**
 * A scrobble not yet confirmed by Last.fm. `uncertain` means a send ended without a clear answer, so
 * Last.fm is checked before retrying (SPEC.md D10).
 */
export interface QueuedScrobble {
  id: string
  playId: string
  seq: number
  artist: string
  track: string
  album: string
  albumArtist?: string
  durationSec: number
  timestamp: string
  /** Live mode: not sent before the track has ended (ISO timestamp). Missing = send right away. */
  sendAfter?: string
  state: 'pending' | 'uncertain'
}

export interface MetaEntry {
  key: string
  value: string
}

export const db = new Dexie('scrobblecue') as Dexie & {
  collection: EntityTable<CollectionItem, 'instanceId'>
  releases: EntityTable<CachedRelease, 'id'>
  lookups: EntityTable<LookupDurations, 'releaseId'>
  corrections: Dexie.Table<Correction, [number, string]>
  plays: EntityTable<Play, 'id'>
  queue: EntityTable<QueuedScrobble, 'id'>
  meta: EntityTable<MetaEntry, 'key'>
}

db.version(1).stores({
  collection: 'instanceId, releaseId, addedAt',
  releases: 'id',
  lookups: 'releaseId',
  corrections: '[releaseId+position], releaseId',
  plays: 'id, releaseId, createdAt',
  queue: 'id, playId, state',
  meta: 'key',
})

export async function getMeta(key: string): Promise<string | undefined> {
  return (await db.meta.get(key))?.value
}

export async function setMeta(key: string, value: string): Promise<void> {
  await db.meta.put({ key, value })
}
