/**
 * @fileoverview In-memory auth context.
 * Access tokens live in JavaScript memory only — never localStorage, never the
 * service-worker cache (A01, AC-security, WO-109 constraints).
 */
import { createContext, useContext } from 'react'

/**
 * @typedef {{
 *   accessToken: string | null,
 *   roles: string[],
 *   userId: string | null,
 *   storedPreference: string | null,
 *   setAuth: (auth: { accessToken: string, roles: string[], userId: string, storedPreference: string | null }) => void,
 *   clearAuth: () => void,
 * }} AuthState
 */

/** @type {React.Context<AuthState>} */
export const AuthContext = createContext({
  accessToken: null,
  roles: [],
  userId: null,
  storedPreference: null,
  setAuth: () => {},
  clearAuth: () => {},
})

/** @returns {AuthState} */
export function useAuth() {
  return useContext(AuthContext)
}
