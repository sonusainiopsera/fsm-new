/**
 * @fileoverview AppearanceProvider — manages the active appearance and exposes
 * a switch control usable from any surface.
 *
 * Switches appearance by mutating only the `data-appearance` attribute on
 * `<html>` — never swapping stylesheets, never remounting the route tree.
 * This keeps the interaction-to-repaint within the 100 ms budget (AC-10).
 *
 * Server reconciliation:
 * The provider accepts an optional `serverPreference` prop (set after auth).
 * When it changes, the preference is treated as authoritative and overwrites
 * the local mirror.
 */
import { createContext, useContext, useState, useEffect, useCallback } from 'react'
import { readMirror, writeMirror } from './appearanceMirror.js'
import { resolveAppearance, applyAppearance, osPrefersDark } from './resolveAppearance.js'

/**
 * @typedef {'LIGHT' | 'DARK' | 'SYSTEM'} PreferenceValue
 * @typedef {{ preference: PreferenceValue | null, setPreference: (p: PreferenceValue) => void }} AppearanceContextValue
 */

/** @type {React.Context<AppearanceContextValue>} */
export const AppearanceContext = createContext({
  preference: null,
  setPreference: () => {},
})

export function useAppearance() {
  return useContext(AppearanceContext)
}

/**
 * @param {{
 *   children: React.ReactNode,
 *   serverPreference?: PreferenceValue | null,
 *   onPreferenceChange?: (p: PreferenceValue) => void
 * }} props
 */
export function AppearanceProvider({ children, serverPreference, onPreferenceChange }) {
  const [preference, setPreferenceState] = useState(() => {
    // Initialise from mirror (synchronous, safe for SSR guard)
    return readMirror() ?? null
  })

  // Apply appearance whenever preference changes
  useEffect(() => {
    const concrete = resolveAppearance(preference)
    applyAppearance(concrete)
  }, [preference])

  // SYSTEM: follow live OS changes without a server write
  useEffect(() => {
    if (preference !== 'SYSTEM') return
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => applyAppearance(osPrefersDark() ? 'dark' : 'light')
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [preference])

  // Server reconciliation: when serverPreference arrives (post-auth), treat as authoritative
  useEffect(() => {
    if (serverPreference === undefined) return
    const canonical = serverPreference ?? null
    setPreferenceState(canonical)
    if (canonical != null) {
      writeMirror(canonical)
    }
  }, [serverPreference])

  const setPreference = useCallback((/** @type {PreferenceValue} */ next) => {
    setPreferenceState(next)
    writeMirror(next)
    if (onPreferenceChange) {
      onPreferenceChange(next)
    }
  }, [onPreferenceChange])

  return (
    <AppearanceContext.Provider value={{ preference, setPreference }}>
      {children}
    </AppearanceContext.Provider>
  )
}
