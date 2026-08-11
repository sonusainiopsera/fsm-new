import { createContext, useContext, useState } from 'react';

/**
 * @typedef {'comfortable' | 'compact'} Density
 */

/**
 * @typedef {Object} DensityContextValue
 * @property {Density} density - Current density mode
 * @property {(d: Density) => void} setDensity - Update density
 */

/** @type {import('react').Context<DensityContextValue>} */
export const DensityContext = createContext({
  density: 'comfortable',
  setDensity: () => {},
});

/**
 * Provides density context to descendants.
 * WO-091 layers persona defaults on top of this provider.
 *
 * @param {{ density?: Density, children: import('react').ReactNode }} props
 */
export function DensityProvider({ density: initialDensity = 'comfortable', children }) {
  const [density, setDensity] = useState(initialDensity);
  return (
    <DensityContext.Provider value={{ density, setDensity }}>
      {children}
    </DensityContext.Provider>
  );
}

/**
 * @returns {DensityContextValue}
 */
export function useDensity() {
  return useContext(DensityContext);
}

/** @type {ReadonlyArray<Density>} */
export const DENSITIES = ['comfortable', 'compact'];

/**
 * Validate that a density value is a known enum member.
 * @param {string} value
 * @returns {value is Density}
 */
export function isValidDensity(value) {
  return DENSITIES.includes(value);
}
