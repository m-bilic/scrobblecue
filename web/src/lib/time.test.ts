import { describe, expect, it } from 'vitest'
import { formatDuration, isoSeconds, parseDuration } from './time'

describe('durations', () => {
  it('formats and parses', () => {
    expect(formatDuration(213)).toBe('3:33')
    expect(formatDuration(3735)).toBe('1:02:15')
    expect(parseDuration('3:33')).toBe(213)
    expect(parseDuration(' 1:02:15 ')).toBe(3735)
  })

  it('rejects text that is not a length', () => {
    expect(parseDuration('abc')).toBeUndefined()
    expect(parseDuration('0:00')).toBeUndefined()
    expect(parseDuration('3:5')).toBeUndefined()
  })
})

it('drops milliseconds from ISO timestamps', () => {
  expect(isoSeconds(new Date('2026-10-27T20:00:00.123Z'))).toBe('2026-10-27T20:00:00Z')
})
