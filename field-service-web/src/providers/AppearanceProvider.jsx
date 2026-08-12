import { createContext, useCallback, useContext, useEffect, useState } from 'react'

/**
 * @typedef {'light' | 'dark'} Appearance
 */

/**
 * @typedef {{ appearance: Appearance, setAppearance: (a: Appearance) => void }} AppearanceContextValue
 */

/** @type {React.Context<AppearanceContextValue>} */
const AppearanceContext = createContext(
  /** @type {AppearanceContextValue} */ ({
    appearance: 'light',
    setAppearance: () => {},
  })
)

/**
 * Reads the current data-appearance from the document root.
 * The inline script in index.html sets this before React mounts.
 * @returns {Appearance}
 */
function resolveInitialAppearance() {
  if (typeof document === 'undefined') return 'light'
  const attr = document.documentElement.getAttribute('data-appearance')
  return attr === 'dark' ? 'dark' : 'light'
}

/**
 * @param {{ children: React.ReactNode }} props
 */
export function AppearanceProvider({ children }) {
  const [appearance, setAppearanceState] = useState(resolveInitialAppearance)

  const setAppearance = useCallback(
    /** @param {Appearance} next */
    (next) => {
      setAppearanceState(next)
      document.documentElement.setAttribute('data-appearance', next)
      try {
        localStorage.setItem('appearance', next)
      } catch {
        // Storage may be unavailable; non-fatal
      }
    },
    []
  )

  // Keep document in sync if state changes externally (e.g., system preference)
  useEffect(() => {
    document.documentElement.setAttribute('data-appearance', appearance)
  }, [appearance])

  return (
    <AppearanceContext.Provider value={{ appearance, setAppearance }}>
      {children}
    </AppearanceContext.Provider>
  )
}

/**
 * @returns {AppearanceContextValue}
 */
export function useAppearance() {
  return useContext(AppearanceContext)
}
