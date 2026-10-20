import { ApiError } from '../api/types'
import { TAG_ERRORS } from '../config/constants'

export interface TagError {
  message: string
  /** True when the fix is to sign in again. */
  signIn: boolean
}

/** Maps a failed tag request to the specific message for its cause. */
export function describeTagError(e: unknown): TagError {
  if (!(e instanceof ApiError)) {
    return { message: e instanceof TypeError ? TAG_ERRORS.network : TAG_ERRORS.generic, signIn: false }
  }
  const msg = (message: string, signIn = false): TagError => ({ message, signIn })
  if (e.status === 400) return msg(TAG_ERRORS.invalidName(e.details?.[0]?.message))
  if (e.status === 401) return msg(TAG_ERRORS.signedOut, true)
  if (e.status === 403 && e.code === 'csrf_invalid') return msg(TAG_ERRORS.csrf)
  if (e.status === 409) return msg(TAG_ERRORS.conflict(e.conflictingSlug))
  if (e.status === 429) return msg(TAG_ERRORS.rateLimited)
  if (e.status === 503) return msg(TAG_ERRORS.unavailable)
  return msg(TAG_ERRORS.generic)
}
