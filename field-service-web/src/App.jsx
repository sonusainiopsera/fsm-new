import React, { useState, useCallback } from 'react';

/**
 * Root application component.
 * Manages the data-appearance attribute on <html> for light/dark switching.
 */
export default function App() {
  const [appearance, setAppearance] = useState(
    () => document.documentElement.getAttribute('data-appearance') || 'light'
  );

  const toggleAppearance = useCallback(() => {
    const next = appearance === 'light' ? 'dark' : 'light';
    document.documentElement.setAttribute('data-appearance', next);
    setAppearance(next);
  }, [appearance]);

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
    </main>
  );
}
