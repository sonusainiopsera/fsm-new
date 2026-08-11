import { createContext, useContext, useState, useMemo } from 'react';
import { resolvePersonaPreset } from './personaDensity.js';

/**
 * @typedef {'comfortable' | 'compact'} Density
 */

/**
 * @typedef {Object} DensityContextValue
 * @property {Density} density - Current density mode
 * @property {(d: Density) => void} setDensity - Update density
 * @property {import('./personaDensity.js').PersonaDensityPreset|null} personaPreset - Active persona preset
 */

/** @type {import('react').Context<DensityContextValue>} */
export const DensityContext = createContext({
  density: 'comfortable',
  setDensity: () => {},
  personaPreset: null,
});

/**
 * Provides density context to descendants.
 * Persona-aware: pass a `persona` prop to resolve defaults from personaDensity.js.
 *
 * @param {{ density?: Density, persona?: string, children: import('react').ReactNode }} props
 */
export function DensityProvider({ density: initialDensity, persona, children }) {
  const preset = useMemo(() => (persona ? resolvePersonaPreset([persona.toUpperCase()]) : null), [persona]);
  const resolvedInitial = initialDensity ?? (preset?.density ?? 'comfortable');
  const [density, setDensity] = useState(resolvedInitial);
  return (
    <DensityContext.Provider value={{ density, setDensity, personaPreset: preset }}>
      {children}
    </DensityContext.Provider>
  );
}

/**
 * Provides persona-aware density context derived from a JWT roles array.
 * The most specific persona preset is resolved and applied automatically.
 *
 * @param {{ roles?: string[], children: import('react').ReactNode }} props
 */
export function PersonaDensityProvider({ roles = [], children }) {
  const preset = useMemo(() => resolvePersonaPreset(roles), [roles]);
  const [density, setDensity] = useState(preset?.density ?? 'comfortable');
  return (
    <DensityContext.Provider value={{ density, setDensity, personaPreset: preset }}>
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
