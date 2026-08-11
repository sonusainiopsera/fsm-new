import React, { useState, useCallback } from 'react';

import CatalogueRoute from './catalogue/CatalogueRoute.jsx';

/**
 * Root application component.
 * Manages the data-appearance attribute on <html> for light/dark switching.
 */
export default function App() {
  const [appearance, setAppearance] = useState(
    () => document.documentElement.getAttribute('data-appearance') || 'light'
  );
  const [view, setView] = useState('home');

  const toggleAppearance = useCallback(() => {
    const next = appearance === 'light' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-appearance', next);
    setAppearance(next);
  }, [appearance]);

  if (view === 'catalogue') {
    return (
      <main>
        <div style={{
          display: 'flex',
          justifyContent: 'flex-end',
          gap: 'var(--space-2)',
          padding: 'var(--space-3) var(--gutter)',
          borderBottom: '1px solid var(--color-border)',
        }}>
          <button
            type="button"
            onClick={() => setView('home')}
            style={{
              padding: 'var(--space-2) var(--space-4)',
              borderRadius: 'var(--radius-control)',
              background: 'transparent',
              color: 'var(--color-text-secondary)',
              border: '1px solid var(--color-border)',
              cursor: 'pointer',
            }}
          >
            ← Back
          </button>
          <button
            type="button"
            onClick={toggleAppearance}
            style={{
              padding: 'var(--space-2) var(--space-4)',
              borderRadius: 'var(--radius-control)',
              background: 'var(--color-accent-base)',
              color: 'var(--color-text-on-accent)',
              border: 'none',
              cursor: 'pointer',
            }}
          >
            {appearance === 'light' ? 'Dark' : 'Light'} Mode
          </button>
        </div>
        <CatalogueRoute />
      </main>
    );
  }

  return (
    <main
      style={{
        minHeight: '100vh',
        background: 'var(--color-surface-base)',
        color: 'var(--color-text-primary)',
        fontFamily: 'var(--font-sans)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        gap: 'var(--space-6)',
      }}
    >
      <h1 style={{ fontSize: 'var(--fs-2xl)', letterSpacing: 'var(--ls-tight)' }}>
        Field Service Platform
      </h1>
      <p style={{ fontSize: 'var(--fs-base)', color: 'var(--color-text-secondary)' }}>
        Design token contract loaded. Current appearance:{' '}
        <strong className="numeric">{appearance}</strong>
      </p>
      <div style={{ display: 'flex', gap: 'var(--space-3)' }}>
        <button
          type="button"
          onClick={toggleAppearance}
          style={{
            padding: 'var(--space-2) var(--space-4)',
            borderRadius: 'var(--radius-control)',
            background: 'var(--color-accent-base)',
            color: 'var(--color-accent-text)',
            border: 'none',
            cursor: 'pointer',
            fontSize: 'var(--fs-md)',
            transitionDuration: 'var(--duration-micro)',
            transitionTimingFunction: 'var(--easing-standard)',
            transitionProperty: 'background',
          }}
        >
          Toggle {appearance === 'light' ? 'Dark' : 'Light'} Mode
        </button>
        <button
          type="button"
          onClick={() => setView('catalogue')}
          style={{
            padding: 'var(--space-2) var(--space-4)',
            borderRadius: 'var(--radius-control)',
            background: 'var(--color-surface-raised)',
            color: 'var(--color-text-primary)',
            border: '1px solid var(--color-border)',
            cursor: 'pointer',
            fontSize: 'var(--fs-md)',
          }}
        >
          View Component Catalogue
        </button>
      </div>
    </main>
  );
}
