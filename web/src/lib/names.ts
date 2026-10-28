/** Discogs disambiguation suffix, e.g. "Prince (2)". */
const DISAMBIGUATION = /\s+\(\d+\)$/

/** Removes Discogs' "(2)"-style suffixes and extra whitespace, for display. */
export function cleanName(name: string | undefined | null): string {
  return (name ?? '').trim().replace(/\s+/g, ' ').replace(DISAMBIGUATION, '')
}

export interface DiscogsArtistCredit {
  name: string
  join?: string
}

/** Joins Discogs artist credits for display: "A & B", "A Feat. B", "A, B". */
export function joinArtists(artists: DiscogsArtistCredit[] | undefined): string {
  if (!artists?.length) return ''
  const joined = artists
    .map((artist, i) => {
      const name = cleanName(artist.name)
      if (i === artists.length - 1) return name
      const join = (artist.join ?? '').trim()
      return join === '' || join === ',' ? `${name}, ` : `${name} ${join} `
    })
    .join('')
  return joined === 'Various' ? 'Various Artists' : joined
}

/** Lower-case, accent-free form for searching. */
export function searchable(text: string): string {
  return text
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .toLowerCase()
}
