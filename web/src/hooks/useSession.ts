import { useQuery } from '@tanstack/react-query'
import { api } from '../api/client'
import type { Me } from '../api/types'

/**
 * Who is connected, or null. The credential cookie is HttpOnly, so the page asks the backend.
 */
export function useSession() {
  return useQuery({
    queryKey: ['me'],
    queryFn: async (): Promise<Me | null> => {
      const { data, response } = await api.GET('/v1/me')
      if (response.status === 401) return null
      if (!response.ok || !data) throw new Error(`Could not check the connection (${response.status})`)
      return { lastfmUser: data.lastfmUser ?? '', discogsUser: data.discogsUser ?? null }
    },
    staleTime: 5 * 60 * 1000,
  })
}

/** Starts a connect flow: asks the backend for the provider's page and goes there. */
export async function connect(provider: 'lastfm' | 'discogs'): Promise<void> {
  const result =
    provider === 'lastfm'
      ? await api.POST('/v1/auth/lastfm/start', { body: { client: 'WEB' } })
      : await api.POST('/v1/auth/discogs/start', { body: { client: 'WEB' } })
  const url = result.data?.authorizeUrl
  if (!url) throw new Error('Could not start connecting. Is the backend running?')
  window.location.assign(url)
}
