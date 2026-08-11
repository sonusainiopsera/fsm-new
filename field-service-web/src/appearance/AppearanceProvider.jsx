import React, { useCallback, useState } from 'react';
import { AppearanceContext } from './AppearanceContext.js';
import { resolveAppearance } from './resolveAppearance.js';
import { writeMirror } from './appearanceMirror.js';

/**
 * Provides appearance state to descendant components.
 *
 * Authority rules:
 *   1. On mount, reads the data-appearance attribute already set by the
 *      pre-paint bootstrap in main.jsx — no layout shift, no initial flash.
 *   2. setSetting updates the data-appearance attribute on <html>, persists
 *      the new value to the localStorage mirror, and calls the optional
 *      onPersist callback (used by TanStack Query mutation to sync with the API).
 *   3. Mutates only the data-attribute; no stylesheet swapping occurs here.
 *
 * @param {{
 *   onPersist?: (setting: import('./AppearanceContext.js').AppearanceSetting) => void,
 *   children: import('react').ReactNode
 * }} props
 */
export default function AppearanceProvider({ onPersist, children }) {
  const [setting, setSettingState] = useState(() => {
    const attr = document.documentElement.getAttribute('data-appearance') || 'light';
    return /** @type {import('./AppearanceContext.js').AppearanceSetting} */ (attr);
  });

  const resolved = resolveAppearance(setting);

  const setSetting = useCallback((newSetting) => {
    const newResolved = resolveAppearance(newSetting);
    document.documentElement.setAttribute('data-appearance', newResolved);
    writeMirror(newSetting);
    setSettingState(newSetting);
    onPersist?.(newSetting);
  }, [onPersist]);

  const toggleAppearance = useCallback(() => {
    setSetting(resolved === 'light' ? 'dark' : 'light');
  }, [resolved, setSetting]);

  return (
    <AppearanceContext.Provider value={{ appearance: resolved, setting, setSetting, toggleAppearance }}>
      {children}
    </AppearanceContext.Provider>
  );
}
