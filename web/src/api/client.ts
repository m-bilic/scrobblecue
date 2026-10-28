import createClient from 'openapi-fetch'
import type { paths } from './schema'

export const APP_VERSION = '0.1.0'

/**
 * Typed client for the ScrobbleCue backend, generated from api/openapi.json (`npm run gen:api`).
 *
 * - `credentials: 'include'` sends the HttpOnly credential cookie; page scripts never see it.
 * - `X-ScrobbleCue` proves the request comes from our own page (CSRF protection for cookie auth).
 */
export const api = createClient<paths>({
  baseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
  credentials: 'include',
  headers: {
    'X-ScrobbleCue': '1',
    'X-Client-Version': `web/${APP_VERSION}`,
  },
})

/** RFC 9457 problem details as returned by the backend. */
export interface Problem {
  type?: string
  title?: string
  status?: number
  detail?: string
}

/** Last path segment of a problem type, e.g. `lastfm-session-revoked`. */
export function problemCode(error: unknown): string | undefined {
  const type = (error as Problem | undefined)?.type
  return type?.substring(type.lastIndexOf('/') + 1)
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem: Problem | undefined,
  ) {
    super(problem?.detail ?? problem?.title ?? `Request failed (${status})`)
  }

  get code(): string | undefined {
    return problemCode(this.problem)
  }
}

/** Unwraps an openapi-fetch result, throwing {@link ApiError} for error responses. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.error !== undefined || !result.response.ok) {
    throw new ApiError(result.response.status, result.error as Problem | undefined)
  }
  return result.data as T
}
