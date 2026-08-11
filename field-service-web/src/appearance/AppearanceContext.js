import { createContext, useContext } from 'react';

/**
 * @typedef {'light'|'dark'|'system'} AppearanceSetting
 * The three stored preference values. The UI attribute is always 'light'|'dark'
 * (resolved via OS media query for 'system').
 */

/**
 * @typedef {Object} AppearanceContextValue
 * @property {'light'|'dark'} appearance   - Resolved value applied to data-appearance
 * @property {AppearanceSetting} setting   - Stored preference (what the user explicitly chose)
 * @property {(s: AppearanceSetting) => void} setSetting  - Update and persist the preference
 * @property {() => void} toggleAppearance - Cycle light → dark → light
 */

/** @type {import('react').Context<AppearanceContextValue>} */
export const AppearanceContext = createContext({
  appearance: 'light',
  setting: 'light',
  setSetting: () => {},
  toggleAppearance: () => {},
});

/**
 * @returns {AppearanceContextValue}
 */
export function useAppearance() {
  return useContext(AppearanceContext);
}

/** @type {ReadonlyArray<AppearanceSetting>} */
export const APPEARANCE_SETTINGS = ['light', 'dark', 'system'];

/**
 * @param {string} value
 * @returns {value is AppearanceSetting}
 */
export function isValidAppearanceSetting(value) {
  return APPEARANCE_SETTINGS.includes(value);
}
