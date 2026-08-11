import { useCallback, useEffect, useState } from 'react';

const STORAGE_KEY = 'fsvc_sidebar_collapsed';
const DRAWER_BREAKPOINT = 768;

function getInitialCollapsed() {
  try {
    return localStorage.getItem(STORAGE_KEY) === 'true';
  } catch {
    return false;
  }
}

/**
 * Manages sidebar collapse state with localStorage persistence.
 *
 * Below the 768 px breakpoint the sidebar switches to an off-canvas drawer
 * pattern regardless of the stored preference (drawer mode wins). The stored
 * preference is preserved so wider viewports restore the user's preference.
 *
 * @returns {{
 *   collapsed: boolean,
 *   toggle: () => void,
 *   isDrawerMode: boolean,
 * }}
 */
export function useSidebarCollapse() {
  const [collapsed, setCollapsed] = useState(getInitialCollapsed);
  const [viewportWidth, setViewportWidth] = useState(
    () => (typeof window !== 'undefined' ? window.innerWidth : DRAWER_BREAKPOINT + 1)
  );

  useEffect(() => {
    const handler = () => setViewportWidth(window.innerWidth);
    window.addEventListener('resize', handler, { passive: true });
    return () => window.removeEventListener('resize', handler);
  }, []);

  const isDrawerMode = viewportWidth < DRAWER_BREAKPOINT;

  const toggle = useCallback(() => {
    setCollapsed((prev) => {
      const next = !prev;
      if (!isDrawerMode) {
        try {
          localStorage.setItem(STORAGE_KEY, String(next));
        } catch {
          // SecurityError in sandboxed contexts — no-op
        }
      }
      return next;
    });
  }, [isDrawerMode]);

  // When entering drawer mode, always start closed
  useEffect(() => {
    if (isDrawerMode) {
      setCollapsed(true);
    } else {
      setCollapsed(getInitialCollapsed());
    }
  }, [isDrawerMode]);

  return { collapsed, toggle, isDrawerMode };
}
