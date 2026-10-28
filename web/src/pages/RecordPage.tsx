import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { api, unwrap } from '../api/client'
import type { PreparedTrack, PreparedTracklist, TrackGroup } from '../api/types'
import { getRelease, positionsWithoutDuration } from '../external/discogs'
import { lookupDurations } from '../external/musicbrainz'
import { scrobblePlay } from '../lib/scrobbler'
import { formatDuration, parseDuration } from '../lib/time'
import { db, type Correction, type Play, type PlayScope } from '../store/db'

const ENDED_OPTIONS = [
  { minutes: 0, label: 'just now' },
  { minutes: 5, label: '5 min ago' },
  { minutes: 15, label: '15 min ago' },
  { minutes: 30, label: '30 min ago' },
  { minutes: 60, label: '1 hour ago' },
]

export function RecordPage() {
  const releaseId = Number(useParams().releaseId)
  const queryClient = useQueryClient()
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [endedMinutesAgo, setEndedMinutesAgo] = useState(0)
  const [editing, setEditing] = useState(false)
  const [lastPlay, setLastPlay] = useState<Play>()

  const item = useQuery({
    queryKey: ['collection-item', releaseId],
    queryFn: async () => (await db.collection.where('releaseId').equals(releaseId).first()) ?? null,
  })

  const prepared = useQuery({
    queryKey: ['prepared', releaseId],
    queryFn: async (): Promise<PreparedTracklist> => {
      const release = await getRelease(releaseId)
      const corrections = await db.corrections.where('releaseId').equals(releaseId).toArray()
      // Only ask MusicBrainz when Discogs is missing lengths
      const missing = positionsWithoutDuration(release)
      const durations = missing.length > 0 ? await lookupDurations(releaseId, allPositions(release)) : {}
      return unwrap(
        await api.POST('/v1/tracklists/prepare', {
          body: {
            release: release as never,
            durations,
            corrections: corrections.map(({ position, title, artist, durationSec, skip }) => ({
              position,
              title,
              artist,
              durationSec,
              skip,
            })),
          },
        }),
      ) as PreparedTracklist
    },
  })

  const scrobble = useMutation({
    mutationFn: (args: { scope: PlayScope; side?: string; tracks: PreparedTrack[] }) =>
      scrobblePlay({
        tracklist: prepared.data!,
        cover: item.data?.cover ?? '',
        ...args,
        endedAt: new Date(Date.now() - endedMinutesAgo * 60_000),
      }),
    onSuccess: (play) => {
      setLastPlay(play)
      setSelected(new Set())
    },
  })

  async function saveCorrection(correction: Correction) {
    await db.corrections.put(correction)
    await queryClient.invalidateQueries({ queryKey: ['prepared', releaseId] })
  }

  if (prepared.isPending) return <p className="muted">Loading tracklist…</p>
  if (prepared.isError) return <p className="error">Could not load this record: {prepared.error.message}</p>

  const tracklist = prepared.data
  const allTracks = [...tracklist.groups.flatMap((g) => g.tracks), ...tracklist.unassigned]
  const selectedTracks = allTracks.filter((t) => selected.has(t.position))
  const estimated = allTracks.filter((t) => t.durationSource === 'DEFAULT').length

  const toggle = (position: string) =>
    setSelected((current) => {
      const next = new Set(current)
      if (next.has(position)) next.delete(position)
      else next.add(position)
      return next
    })

  return (
    <section className="record">
      <Link to="/" className="back">
        ← Collection
      </Link>
      <div className="record-head">
        {item.data?.cover && <img src={item.data.cover} alt="" className="cover" />}
        <div>
          <h1>{tracklist.album}</h1>
          <p className="artist">{tracklist.albumArtist}</p>
          <p className="muted">
            {item.data?.year ? `${item.data.year} · ` : ''}
            {item.data?.formats.join(', ')}
            {' · '}
            <a href={`https://www.discogs.com/release/${releaseId}`} target="_blank" rel="noreferrer">
              Discogs
            </a>
          </p>
        </div>
      </div>

      <div className="controls">
        <label>
          Finished{' '}
          <select value={endedMinutesAgo} onChange={(e) => setEndedMinutesAgo(Number(e.target.value))}>
            {ENDED_OPTIONS.map((o) => (
              <option key={o.minutes} value={o.minutes}>
                {o.label}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={scrobble.isPending}
          onClick={() => scrobble.mutate({ scope: 'WHOLE', tracks: tracklist.groups.flatMap((g) => g.tracks) })}
        >
          Scrobble whole record
        </button>
        <button
          type="button"
          disabled={scrobble.isPending || selectedTracks.length === 0}
          onClick={() => scrobble.mutate({ scope: 'TRACKS', tracks: selectedTracks })}
        >
          Scrobble selected ({selectedTracks.length})
        </button>
        <button type="button" className="link" onClick={() => setEditing((e) => !e)}>
          {editing ? 'Done editing' : 'Edit tracks'}
        </button>
      </div>

      {estimated > 0 && (
        <p className="hint">
          {estimated} track length{estimated > 1 ? 's are' : ' is'} unknown and estimated as 3:30 (marked ~). Use "Edit
          tracks" to correct them – ScrobbleCue remembers it in this browser.
        </p>
      )}
      <PlayResult play={lastPlay} error={scrobble.error} />

      {tracklist.groups.map((group) => (
        <Side
          key={group.label}
          group={group}
          sideScrobblable={tracklist.sideScrobblable}
          busy={scrobble.isPending}
          selected={selected}
          editing={editing}
          onToggle={toggle}
          onScrobbleSide={() => scrobble.mutate({ scope: 'SIDE', side: group.label, tracks: group.tracks })}
          onCorrect={(c) => saveCorrection({ ...c, releaseId })}
        />
      ))}
      {tracklist.unassigned.length > 0 && (
        <Side
          group={{ kind: 'ALL', label: 'Other', tracks: tracklist.unassigned }}
          sideScrobblable={false}
          busy={scrobble.isPending}
          selected={selected}
          editing={editing}
          onToggle={toggle}
          onScrobbleSide={() => undefined}
          onCorrect={(c) => saveCorrection({ ...c, releaseId })}
        />
      )}
    </section>
  )
}

function Side(props: {
  group: TrackGroup
  sideScrobblable: boolean
  busy: boolean
  selected: Set<string>
  editing: boolean
  onToggle: (position: string) => void
  onScrobbleSide: () => void
  onCorrect: (c: Omit<Correction, 'releaseId'>) => void
}) {
  const { group } = props
  const title = group.kind === 'SIDE' ? `Side ${group.label}` : group.kind === 'DISC' ? `Disc ${group.label}` : group.label === 'ALL' ? 'Tracks' : group.label
  const total = group.tracks.reduce((sum, t) => sum + t.durationSec, 0)

  return (
    <div className="side">
      <div className="side-head">
        <h2>
          {title} <span className="muted">{formatDuration(total)}</span>
        </h2>
        {props.sideScrobblable && group.kind === 'SIDE' && (
          <button type="button" className="primary" disabled={props.busy} onClick={props.onScrobbleSide}>
            Scrobble side {group.label}
          </button>
        )}
      </div>
      <ul className="tracks">
        {group.tracks.map((track) => (
          <TrackRow
            key={track.position}
            track={track}
            checked={props.selected.has(track.position)}
            editing={props.editing}
            onToggle={() => props.onToggle(track.position)}
            onCorrect={props.onCorrect}
          />
        ))}
      </ul>
    </div>
  )
}

function TrackRow(props: {
  track: PreparedTrack
  checked: boolean
  editing: boolean
  onToggle: () => void
  onCorrect: (c: Omit<Correction, 'releaseId'>) => void
}) {
  const { track } = props
  const [length, setLength] = useState(formatDuration(track.durationSec))

  function save(skip: boolean) {
    const seconds = parseDuration(length)
    props.onCorrect({
      position: track.position,
      durationSec: seconds && seconds !== track.durationSec ? seconds : track.durationSource === 'CORRECTION' ? track.durationSec : undefined,
      skip,
    })
  }

  return (
    <li className={track.scrobblable ? '' : 'not-scrobbled'}>
      <label className="track">
        <input type="checkbox" checked={props.checked} onChange={props.onToggle} disabled={!track.scrobblable} />
        <span className="pos">{track.position}</span>
        <span className="title">
          {track.title}
          {track.artist !== '' && <span className="muted"> – {track.artist}</span>}
        </span>
        {!props.editing && (
          <span className="len" title={track.durationSource === 'DEFAULT' ? 'Length unknown – estimated' : `Length from ${track.durationSource.toLowerCase()}`}>
            {track.durationSource === 'DEFAULT' ? '~' : ''}
            {formatDuration(track.durationSec)}
          </span>
        )}
      </label>
      {props.editing && (
        <span className="edit">
          <input
            aria-label={`Length of ${track.title}`}
            value={length}
            size={6}
            onChange={(e) => setLength(e.target.value)}
            onBlur={() => save(!track.scrobblable && track.durationSec > 30)}
          />
          <label>
            <input type="checkbox" checked={!track.scrobblable && track.durationSec > 30} onChange={(e) => save(e.target.checked)} /> skip
          </label>
        </span>
      )}
    </li>
  )
}

function PlayResult({ play, error }: { play?: Play; error: Error | null }) {
  if (error) return <p className="error">Scrobbling failed: {error.message}</p>
  if (!play) return null
  if (play.status === 'done') {
    return (
      <p className="success">
        Scrobbled {play.accepted} track{play.accepted === 1 ? '' : 's'} to Last.fm
        {play.ignored > 0 ? ` (${play.ignored} ignored by Last.fm)` : ''}.
      </p>
    )
  }
  return <p className="hint">{play.message ?? 'Saved – will be sent to Last.fm shortly.'}</p>
}

function allPositions(release: unknown): string[] {
  type Track = { position?: string; type_?: string; sub_tracks?: Track[] }
  const out: string[] = []
  const visit = (tracks: Track[] | undefined) => {
    for (const t of tracks ?? []) {
      if (t.type_ === 'heading') continue
      if (t.type_ === 'index') visit(t.sub_tracks)
      else out.push((t.position ?? '').trim())
    }
  }
  visit((release as { tracklist?: Track[] }).tracklist)
  return out
}
