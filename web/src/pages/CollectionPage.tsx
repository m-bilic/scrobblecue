import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../api/client'
import { syncCollection, type SyncProgress } from '../external/discogs'
import { searchable } from '../lib/names'
import { timeAgo } from '../lib/time'
import { db, getMeta } from '../store/db'

export function CollectionPage() {
  const queryClient = useQueryClient()
  const [search, setSearch] = useState('')
  const [progress, setProgress] = useState<SyncProgress>()

  const collection = useQuery({
    queryKey: ['collection'],
    queryFn: async () => ({
      items: await db.collection.orderBy('addedAt').reverse().toArray(),
      syncedAt: await getMeta('collectionSyncedAt'),
    }),
  })

  const sync = useMutation({
    mutationFn: () => syncCollection(setProgress),
    onSettled: () => {
      setProgress(undefined)
      void queryClient.invalidateQueries({ queryKey: ['collection'] })
    },
  })

  // First visit after connecting Discogs: load the collection once automatically
  const neverSynced = collection.isSuccess && !collection.data.syncedAt
  useEffect(() => {
    if (neverSynced && sync.isIdle) sync.mutate()
  }, [neverSynced, sync])

  const items = useMemo(() => {
    const all = collection.data?.items ?? []
    const query = searchable(search.trim())
    return query ? all.filter((i) => searchable(`${i.artist} ${i.title}`).includes(query)) : all
  }, [collection.data, search])

  return (
    <section>
      <div className="toolbar">
        <input
          type="search"
          placeholder="Search artist or title"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          aria-label="Search your collection"
        />
        <div className="sync">
          <span className="muted">
            {sync.isPending
              ? progress
                ? `Loading page ${progress.page} of ${progress.pages}…`
                : 'Loading collection…'
              : collection.data?.syncedAt
                ? `${collection.data.items.length} records · synced ${timeAgo(collection.data.syncedAt)}`
                : ''}
          </span>
          <button type="button" onClick={() => sync.mutate()} disabled={sync.isPending}>
            Refresh
          </button>
        </div>
      </div>

      {sync.isError && (
        <p className="error">
          {sync.error instanceof ApiError && sync.error.code === 'discogs-rate-limited'
            ? 'Discogs is busy. Try refreshing in a minute.'
            : `Loading the collection failed: ${sync.error.message}`}
        </p>
      )}

      <ul className="grid">
        {items.map((item) => (
          <li key={item.instanceId}>
            <Link to={`/record/${item.releaseId}`} className="tile">
              {item.cover ? <img src={item.cover} alt="" loading="lazy" /> : <div className="no-cover" />}
              <span className="tile-title">{item.title}</span>
              <span className="tile-artist">{item.artist}</span>
              {!item.isVinyl && <span className="badge">{item.formats[0]}</span>}
            </Link>
          </li>
        ))}
      </ul>
      {collection.isSuccess && items.length === 0 && !sync.isPending && (
        <p className="muted">{search ? 'Nothing matches your search.' : 'Your collection is empty.'}</p>
      )}
    </section>
  )
}
