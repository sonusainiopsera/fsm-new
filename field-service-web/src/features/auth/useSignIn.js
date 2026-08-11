/**
 * @fileoverview useSignIn — TanStack Query mutation hook for the sign-in flow.
 *
 * Responsibilities:
 * 1. POST /api/v1/auth/login with credentials:include so the browser stores
 *    the HttpOnly refresh cookie returned by the server.
 * 2. Validate the response shape at the boundary BEFORE the token reaches the store.
 * 3. Store the access token in module-scoped memory via tokenStore.setToken() and
 *    update React state via AuthContext.setAuth() — never localStorage or sessionStorage.
 * 4. Navigate to the role-appropriate landing route on success.
 * 5. Expose attemptBootRefresh() for mount-time silent re-authentication from cookie.
 *
 * SECURITY RULES:
 * - retry: false — never retry 401 (retrying auth amplifies lockout; A01, WO-186).
 * - API error messages are passed through unmodified — no UI inference of account existence (AC-6).
 * - validateLoginResponse() throws before setToken() on shape mismatch (AC-12 boundary guard).
 */

import { useMutation } from '@tanstack/react-query'
import { useCallback } from 'react'
import { useNavigate } from 'react-router-dom'

import { useAuth } from '../../app/AuthContext.js'
import { setToken, refreshToken } from '../../api/tokenStore.js'

const LOGIN_ENDPOINT = '/api/v1/auth/login'

// ── Role → landing route ──────────────────────────────────────────────────────

/**
 * Maps roles to the role-appropriate landing route.
 * Technician is checked first per the field-first BR-35 design priority.
 *
 * @param {string[]} roles
 * @returns {string}
 */
export function landingRouteForRoles(roles) {
  const roleSet = new Set(roles)
  if (roleSet.has('TECHNICIAN')) return '/field'
  if (roleSet.has('CUSTOMER')) return '/portal'
  if (roleSet.has('MANAGER')) return '/operations'
  return '/dispatch' // DISPATCHER, ADMIN
}

// ── Boundary validation ───────────────────────────────────────────────────────

/**
 * Runtime boundary validation of the login response shape.
 * Throws TypeError if the shape is invalid so malformed data never reaches the token store.
 *
 * @param {unknown} body
 * @returns {{ accessToken: string, tokenType: string, expiresIn: number, userId: string, displayName: string, roles: string[], storedPreference?: string | null }}
 */
export function validateLoginResponse(body) {
  if (
    !body ||
    typeof body !== 'object' ||
    typeof /** @type {any} */ (body).accessToken !== 'string' ||
    !/** @type {any} */ (body).accessToken ||
    !Array.isArray(/** @type {any} */ (body).roles) ||
    typeof /** @type {any} */ (body).userId !== 'string'
  ) {
    throw new TypeError('Login response failed boundary validation: unexpected shape')
  }
  return /** @type {any} */ (body)
}

// ── JWT payload parser (display only) ────────────────────────────────────────

/**
 * Parses the payload section of a JWT without signature verification.
 * Used ONLY to extract user claims from a server-issued token after a successful
 * boot-time refresh — for display purposes, not security decisions.
 *
 * @param {string} token
 * @returns {{ sub?: string, roles?: string[] } | null}
 */
export function parseJwtPayload(token) {
  try {
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const raw = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    // Pad to 4-byte boundary
    const padded = raw + '='.repeat((4 - (raw.length % 4)) % 4)
    return JSON.parse(atob(padded))
  } catch {
    return null
  }
}

// ── Error helpers ─────────────────────────────────────────────────────────────

/**
 * Parses the Retry-After header value into seconds.
 * @param {string | null} header
 * @returns {number | null}
 */
function parseRetryAfterHeader(header) {
  if (!header) return null
  const n = parseInt(header, 10)
  return isNaN(n) ? null : n
}

// ── Hook ──────────────────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   status: number,
 *   code: string,
 *   message: string,
 *   fieldErrors: Array<{ field: string, message: string }>,
 *   traceId: string | null,
 *   retryAfterSeconds: number | null
 * }} LoginError
 *
 * @typedef {{
 *   signIn: (payload: { email: string, password: string }) => void,
 *   isPending: boolean,
 *   error: LoginError | null,
 *   reset: () => void,
 *   attemptBootRefresh: () => Promise<boolean>
 * }} UseSignInResult
 */

/**
 * Sign-in hook. Wire into the SignInPage component.
 *
 * @returns {UseSignInResult}
 */
export function useSignIn() {
  const { setAuth } = useAuth()
  const navigate = useNavigate()

  /**
   * Attempt a silent refresh from the HttpOnly cookie on page load.
   * Navigates away to the role-appropriate landing route on success.
   * Returns true if the user was already signed in; false to show the form.
   *
   * @returns {Promise<boolean>}
   */
  const attemptBootRefresh = useCallback(async () => {
    try {
      const token = await refreshToken()
      // refreshToken() already called setToken() internally — no need to repeat it.
      // Parse claims for display only (no security decisions made from client-side decode).
      const payload = parseJwtPayload(token)
      const roles = payload?.roles ?? []
      setAuth({
        accessToken: token,
        roles,
        userId: payload?.sub ?? null,
        storedPreference: null,
      })
      navigate(landingRouteForRoles(roles), { replace: true })
      return true
    } catch {
      return false
    }
  }, [setAuth, navigate])

  const mutation = useMutation({
    mutationFn: async ({ email, password }) => {
      let res
      try {
        res = await fetch(LOGIN_ENDPOINT, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            'Accept': 'application/json',
          },
          credentials: 'include', // stores the HttpOnly refresh cookie
          body: JSON.stringify({ email, password }),
        })
      } catch {
        throw {
          status: 0,
          code: 'NETWORK_ERROR',
          message: 'Unable to connect. Please check your connection and try again.',
          fieldErrors: [],
          traceId: null,
          retryAfterSeconds: null,
        }
      }

      const body = await res.json().catch(() => null)

      if (!res.ok) {
        const env = (body && typeof body === 'object') ? body : {}
        /** @type {LoginError} */
        const err = {
          status: res.status,
          code: env.code ?? `HTTP_${res.status}`,
          // Pass API message through verbatim — AC-6: never infer account existence
          message: env.message ?? 'Authentication failed. Please try again.',
          fieldErrors: Array.isArray(env.fieldErrors) ? env.fieldErrors : [],
          traceId: env.traceId ?? null,
          // Retry-After: prefer body field (mock fixtures), fall back to header
          retryAfterSeconds:
            typeof env.retryAfterSeconds === 'number'
              ? env.retryAfterSeconds
              : parseRetryAfterHeader(res.headers.get('Retry-After')),
        }
        throw err
      }

      // Boundary validation before the token reaches the store
      return validateLoginResponse(body)
    },
    retry: false, // NEVER retry auth requests — A01, WO-186 constraint
    onSuccess(data) {
      setToken(data.accessToken)
      setAuth({
        accessToken: data.accessToken,
        roles: data.roles,
        userId: data.userId,
        storedPreference: data.storedPreference ?? null,
      })
      navigate(landingRouteForRoles(data.roles), { replace: true })
    },
  })

  return {
    signIn: mutation.mutate,
    isPending: mutation.isPending,
    error: /** @type {LoginError | null} */ (mutation.error),
    reset: mutation.reset,
    attemptBootRefresh,
  }
}
