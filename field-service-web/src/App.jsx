import React, { useCallback, useEffect, useState } from 'react';

/**
 * Root application component.
 * Manages the light/dark appearance toggle (data-appearance attribute on <html>).
 * All visual styling comes from design tokens loaded in src/styles/index.css.
 */
function App() {
  const [appearance, setAppearance] = useState(() => {
    return localStorage.getItem('fs-appearance') || 'light';
  });

  useEffect(() => {
    if (appearance === 'dark') {
      document.documentElement.setAttribute('data-appearance', 'dark');
    } else {
      document.documentElement.removeAttribute('data-appearance');
    }
    localStorage.setItem('fs-appearance', appearance);
  }, [appearance]);

  const toggleAppearance = useCallback(() => {
    setAppearance((prev) => (prev === 'light' ? 'dark' : 'light'));
  }, []);

  return (
    <div
      style={{
        fontFamily: 'var(--fs-font-family)',
        color: 'var(--fs-color-text-primary)',
        backgroundColor: 'var(--fs-color-surface)',
        minHeight: '100dvh',
        padding: 'var(--fs-space-6)',
      }}
    >
      <button
        aria-label={`Switch to ${appearance === 'light' ? 'dark' : 'light'} mode`}
        onClick={toggleAppearance}
        style={{
          backgroundColor: 'var(--fs-accent-default)',
          color: 'var(--fs-accent-on)',
          borderRadius: 'var(--fs-radius-control)',
          padding: 'var(--fs-space-2)',
          border: 'none',
          cursor: 'pointer',
          transitionDuration: 'var(--fs-duration-micro)',
        }}
      >
        {appearance === 'light' ? 'Dark mode' : 'Light mode'}
      </button>

      <p
        className="numeric"
        style={{
          marginTop: 'var(--fs-space-4)',
          fontSize: 'var(--fs-text-xl)',
          color: 'var(--fs-color-text-secondary)',
          letterSpacing: 'var(--fs-tracking-tight)',
        }}
      >
        12,345.67
      </p>
    </div>
  );
}

export default App;
