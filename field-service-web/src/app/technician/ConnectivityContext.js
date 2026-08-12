import { createContext, useContext } from 'react';
import { NetworkOfflineError } from '../../app/useNetworkStatus.js';

/**
 * @typedef {{
 *   isOnline: boolean,
 *   isOffline: boolean,
 *   cachedAt: Date|null,
 *   assertOnline: () => void
 * }} ConnectivityValue
 */

/** @type {import('react').Context<ConnectivityValue>} */
export const ConnectivityContext = createContext({
  isOnline: true,
  isOffline: false,
  cachedAt: null,
  assertOnline: () => {},
});

/**
 * Consume connectivity state anywhere inside TechnicianShell.
 * @returns {ConnectivityValue}
 */
export function useConnectivityContext() {
  return useContext(ConnectivityContext);
}

export { NetworkOfflineError };
