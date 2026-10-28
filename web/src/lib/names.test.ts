import { describe, expect, it } from 'vitest'
import { cleanName, joinArtists, searchable } from './names'

describe('cleanName', () => {
  it('removes Discogs disambiguation suffixes and extra spaces', () => {
    expect(cleanName('Nemanja (6)')).toBe('Nemanja')
    expect(cleanName('  Very   Long  Jam ')).toBe('Very Long Jam')
    expect(cleanName('Live (1979)')).toBe('Live')
  })
})

describe('joinArtists', () => {
  it('uses Discogs join values', () => {
    expect(joinArtists([{ name: 'Prince (2)', join: '&' }, { name: 'The Revolution' }])).toBe('Prince & The Revolution')
    expect(joinArtists([{ name: 'Hornsman Coyote', join: ',' }, { name: 'Soulcraft (4)' }])).toBe('Hornsman Coyote, Soulcraft')
  })

  it('names compilations like Last.fm does', () => {
    expect(joinArtists([{ name: 'Various' }])).toBe('Various Artists')
  })
})

describe('searchable', () => {
  it('ignores case and accents', () => {
    expect(searchable('Boillat Thérace')).toBe('boillat therace')
  })
})
