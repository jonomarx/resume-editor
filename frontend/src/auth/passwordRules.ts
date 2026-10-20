import { PASSWORD_MAX_BYTES, PASSWORD_MESSAGES } from '../config/constants'

// Control characters U+0000 to U+001F and U+007F, mirroring the server rule for sign-up passwords.
// eslint-disable-next-line no-control-regex
const CONTROL = /[\u0000-\u001F\u007F]/

export const passwordBytes = (value: string): number => new TextEncoder().encode(value).length

/** The first sign-up password rule the value breaks, or null. Login never uses this (any text is just a wrong password). */
export function signUpPasswordProblem(value: string): string | null {
  if (CONTROL.test(value)) return PASSWORD_MESSAGES.controlCharacter
  if (passwordBytes(value) > PASSWORD_MAX_BYTES) return PASSWORD_MESSAGES.tooLong
  return null
}
