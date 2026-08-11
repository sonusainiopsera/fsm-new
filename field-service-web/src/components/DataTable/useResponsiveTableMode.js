import { useState, useEffect, useRef } from 'react';

/**
 * @typedef {'table' | 'cards'} TableMode
 */

const CARD_BREAKPOINT = 768;

/**
 * Observes container width and returns 'table' or 'cards'.
 * Uses ResizeObserver (container-width) rather than window width (viewport).
 *
 * @returns {{ mode: TableMode, containerRef: import('react').RefObject<HTMLDivElement> }}
 */
export function useResponsiveTableMode() {
  const containerRef = useRef(null);
  const [mode, setMode] = useState('table');

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    if (typeof ResizeObserver === 'undefined') return;

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        const width = entry.contentRect.width;
        setMode(width < CARD_BREAKPOINT ? 'cards' : 'table');
      }
    });

    observer.observe(el);

    const initialWidth = el.getBoundingClientRect().width;
    setMode(initialWidth < CARD_BREAKPOINT ? 'cards' : 'table');

    return () => observer.disconnect();
  }, []);

  return { mode, containerRef };
}
