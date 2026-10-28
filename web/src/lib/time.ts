/** 213 → "3:33". */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = String(seconds % 60).padStart(2, '0')
  return h > 0 ? `${h}:${String(m).padStart(2, '0')}:${s}` : `${m}:${s}`
}

/** "3:33" or "1:02:15" → seconds; undefined when not a valid duration. */
export function parseDuration(text: string): number | undefined {
  const match = /^(?:(\d+):)?(\d{1,3}):(\d{2})$/.exec(text.trim())
  if (!match) return undefined
  const seconds = Number(match[1] ?? 0) * 3600 + Number(match[2]) * 60 + Number(match[3])
  return seconds > 0 ? seconds : undefined
}

/** ISO timestamp without milliseconds, as the backend expects. */
export function isoSeconds(date: Date): string {
  return date.toISOString().replace(/\.\d{3}Z$/, 'Z')
}

/** "5 minutes ago", "yesterday", … */
export function timeAgo(iso: string, now = Date.now()): string {
  const minutes = Math.round((now - Date.parse(iso)) / 60000)
  if (minutes < 1) return 'just now'
  if (minutes < 60) return `${minutes} min ago`
  const hours = Math.round(minutes / 60)
  if (hours < 24) return `${hours} h ago`
  const days = Math.round(hours / 24)
  return days === 1 ? 'yesterday' : `${days} days ago`
}
