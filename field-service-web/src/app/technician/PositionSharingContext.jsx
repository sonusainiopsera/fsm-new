/**
 * @fileoverview PositionSharingContext — bridges position reporting state from
 * JobDetailView (where the hook runs) to TechnicianShell (where the indicator lives).
 */
import { createContext, useContext, useState, useCallback } from 'react'

const PositionSharingContext = createContext({ isSharing: false, setIsSharing: () => {} })

export function PositionSharingProvider({ children }) {
  const [isSharing, setIsSharing] = useState(false)

  const update = useCallback((val) => setIsSharing(Boolean(val)), [])

  return (
    <PositionSharingContext.Provider value={{ isSharing, setIsSharing: update }}>
      {children}
    </PositionSharingContext.Provider>
  )
}

export function usePositionSharing() {
  return useContext(PositionSharingContext)
}
