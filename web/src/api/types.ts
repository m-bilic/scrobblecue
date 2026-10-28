import type { components } from './schema'

type Schemas = components['schemas']

/**
 * The generator marks every response field optional; the backend always sends them (records without
 * nulls), so these aliases make them required for convenience.
 */
type DeepRequired<T> = T extends (infer U)[]
  ? DeepRequired<U>[]
  : T extends object
    ? { [K in keyof T]-?: DeepRequired<NonNullable<T[K]>> }
    : T

export type PreparedTracklist = DeepRequired<Schemas['PreparedTracklist']>
export type TrackGroup = DeepRequired<Schemas['TrackGroup']>
export type PreparedTrack = DeepRequired<Schemas['PreparedTrack']>
export type TimelineEntry = DeepRequired<Schemas['TimelineEntry']>
export type ScrobbleResult = Schemas['ScrobbleResult']
export type ScrobbleItem = Schemas['ScrobbleItem']
export type RecentScrobble = DeepRequired<Schemas['RecentScrobble']>
export type TrackCorrection = Schemas['TrackCorrection']

export interface Me {
  lastfmUser: string
  discogsUser: string | null
}
