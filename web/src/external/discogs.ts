import { api, ApiError, type Problem } from '../api/client'
import { joinArtists, cleanName, type DiscogsArtistCredit } from '../lib/names'
import { db, setMeta, type CollectionItem } from '../store/db'

/** Shape of one item in a Discogs collection page (only the fields we use). */
interface DiscogsCollectionRelease {
  id: number
  instance_id: number
  date_added: string
  basic_information: {
    title: string
    year: number
    thumb: string
    cover_image: string
    artists: DiscogsArtistCredit[]
    formats: { name: string }[]
  }
}

interface DiscogsCollectionPage {
  pagination: { page: number; pages: number; items: number }
  releases: DiscogsCollectionRelease[]
}

export interface SyncProgress {
  page: number
  pages: number
}

/**
 * Loads the whole collection through the backend (which signs the request and forwards it) and
 * replaces the local copy. Runs once after connecting Discogs, then only on request.
 */
export async function syncCollection(onProgress?: (p: SyncProgress) => void): Promise<number> {
  const items: CollectionItem[] = []
  let page = 1
  let pages = 1
  do {
    const result = await api.GET('/v1/discogs/collection', { params: { query: { page } } })
    if (!result.response.ok) {
      throw new ApiError(result.response.status, result.error as Problem | undefined)
    }
    // The backend passes Discogs' JSON through unchanged
    const data = result.data as unknown as DiscogsCollectionPage
    pages = data.pagination.pages
    onProgress?.({ page, pages })
    for (const release of data.releases) {
      const info = release.basic_information
      items.push({
        instanceId: release.instance_id,
        releaseId: release.id,
        title: cleanName(info.title),
        artist: joinArtists(info.artists),
        year: info.year,
        formats: info.formats.map((f) => f.name),
        isVinyl: info.formats.some((f) => f.name === 'Vinyl'),
        thumb: info.thumb,
        cover: info.cover_image,
        addedAt: release.date_added,
      })
    }
    page++
  } while (page <= pages)

  await db.transaction('rw', db.collection, db.meta, async () => {
    await db.collection.clear()
    await db.collection.bulkPut(items)
    await setMeta('collectionSyncedAt', new Date().toISOString())
  })
  return items.length
}

export class DiscogsRateLimitError extends Error {
  constructor() {
    super('Discogs is busy (too many requests). Wait a minute and try again.')
  }
}

/**
 * The release as Discogs returns it. Public data, fetched directly from the user's browser (no key, the
 * user's own rate limit) and cached locally.
 */
export async function getRelease(releaseId: number): Promise<unknown> {
  const cached = await db.releases.get(releaseId)
  if (cached) return cached.json
  const response = await fetch(`https://api.discogs.com/releases/${releaseId}`)
  if (response.status === 429) throw new DiscogsRateLimitError()
  if (!response.ok) throw new Error(`Discogs returned ${response.status} for release ${releaseId}`)
  const json: unknown = await response.json()
  await db.releases.put({ id: releaseId, json, fetchedAt: new Date().toISOString() })
  return json
}

/** Positions in the release that Discogs has no length for (headings excluded). */
export function positionsWithoutDuration(release: unknown): string[] {
  type Track = { position?: string; type_?: string; duration?: string; sub_tracks?: Track[] }
  const missing: string[] = []
  const visit = (tracks: Track[] | undefined) => {
    for (const t of tracks ?? []) {
      if (t.type_ === 'heading') continue
      if (t.type_ === 'index') {
        visit(t.sub_tracks)
        continue
      }
      if (!t.duration?.trim()) missing.push((t.position ?? '').trim())
    }
  }
  visit((release as { tracklist?: Track[] }).tracklist)
  return missing
}
