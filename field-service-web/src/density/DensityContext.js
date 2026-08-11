/**
 * @fileoverview Density context — controls information density across DataTable, Chip, FormField.
 * WO-091 will layer persona-specific defaults on top of this context.
 */
import { createContext, useContext, useState } from 'react'

/** @typedef {'comfortable' | 'compact'} Density */

/** @type {React.Context<{ density: Density, setDensity: (d: Density) => void }>} */
export const DensityContext = createContext({ density: 'comfortable', setDensity: () => {} })

/**
 * @param {{ density?: Density, children: React.ReactNode }} props
 */
export function DensityProvider({ density: initialDensity = 'comfortable', children }) {
  const [density, setDensity] = useState(initialDensity)
  return (
    // @ts-ignore — jsx handled by vite
    <DensityContext.Provider value={{ density, setDensity }}>
      {children}
    </DensityContext.Provider>
  )
}

/** @returns {{ density: Density, setDensity: (d: Density) => void }} */
export function useDensity() {
  return useContext(DensityContext)
}
