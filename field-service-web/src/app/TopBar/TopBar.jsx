import React from 'react';
import { useAppearance } from '../../appearance/AppearanceContext.js';
import { useAuth } from '../AuthContext.js';
import { useNetworkStatus } from '../useNetworkStatus.js';

import styles from './TopBar.module.css';

/**
 * Application top bar — banner landmark.
 *
 * Hosts the mobile menu trigger, the appearance switch, the network status
 * indicator, and the account menu. Composes exclusively from token values
 * (no hard-coded colours or measurements).
 *
 * @param {{
 *   onMenuToggle?: () => void,
 *   isDrawerMode?: boolean,
 * }} props
 */
export function TopBar({ onMenuToggle, isDrawerMode = false }) {
  const { appearance, toggleAppearance } = useAppearance();
  const { token, clearToken, isAuthenticated } = useAuth();
  const { isOffline } = useNetworkStatus();

  return (
    <header className={styles.topBar} role="banner">
      <a href="#main-content" className={styles.skipLink}>
        Skip to content
      </a>

      <div className={styles.start}>
        {isDrawerMode && (
          <button
            type="button"
            className={styles.menuBtn}
            onClick={onMenuToggle}
            aria-label="Open navigation menu"
            aria-expanded={false}
          >
            ☰
          </button>
        )}
        <span className={styles.logoMark} aria-hidden="true">⚙</span>
        <span className={styles.productName}>Field Service</span>
      </div>

      <div className={styles.end}>
        {isOffline && (
          <div
            className={styles.offlineBadge}
            role="status"
            aria-live="polite"
            aria-label="No network connection"
          >
            <span aria-hidden="true">⚡</span>
            <span className={styles.offlineLabel}>Offline</span>
          </div>
        )}

        <button
          type="button"
          className={styles.iconBtn}
          onClick={toggleAppearance}
          aria-label={`Switch to ${appearance === 'light' ? 'dark' : 'light'} mode`}
          title={`Switch to ${appearance === 'light' ? 'dark' : 'light'} mode`}
        >
          {appearance === 'light' ? '☾' : '☀'}
        </button>

        {isAuthenticated && (
          <button
            type="button"
            className={styles.accountBtn}
            aria-label={`Account menu for ${token?.displayName ?? 'user'}`}
            onClick={clearToken}
            title="Sign out"
          >
            <span className={styles.avatar} aria-hidden="true">
              {(token?.displayName?.[0] ?? token?.roles?.[0]?.[0] ?? '?').toUpperCase()}
            </span>
          </button>
        )}
      </div>
    </header>
  );
}
