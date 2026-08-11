import React, { useCallback, useEffect, useRef } from 'react';
import { useAuth } from '../AuthContext.js';
import { filterNavItems } from '../navigation.js';

import styles from './Sidebar.module.css';

const ICON_MAP = {
  grid: '⊞',
  briefcase: '⊟',
  'bar-chart': '≡',
  inbox: '☐',
};

/**
 * Application sidebar navigation.
 *
 * USABILITY NOTE: navigation items are filtered by the roles claim for
 * cognitive clarity only — server-side enforcement remains the sole security
 * control (A01, BR-19). A manually crafted URL to an unauthorised route
 * resolves to a server 403, never to a client-side grant.
 *
 * Collapse state is managed by the parent (AppShell) via useSidebarCollapse
 * so a single source of truth drives both the grid layout and the nav display.
 *
 * @param {{
 *   collapsed: boolean,
 *   isDrawerMode: boolean,
 *   onToggle: () => void,
 *   onNavigation?: (path: string) => void,
 *   currentPath?: string,
 * }} props
 */
export function Sidebar({ collapsed, isDrawerMode, onToggle, onNavigation, currentPath = '/' }) {
  const { token } = useAuth();
  const roles = token?.roles ?? [];
  const navItems = filterNavItems(roles);
  const navRef = useRef(null);

  const handleNav = useCallback((path) => {
    onNavigation?.(path);
    if (isDrawerMode) onToggle();
  }, [onNavigation, isDrawerMode, onToggle]);

  useEffect(() => {
    if (!isDrawerMode || collapsed) return;
    const handleKey = (e) => {
      if (e.key === 'Escape') onToggle();
    };
    window.addEventListener('keydown', handleKey);
    return () => window.removeEventListener('keydown', handleKey);
  }, [isDrawerMode, collapsed, onToggle]);

  const sidebarClass = [
    styles.sidebar,
    collapsed && !isDrawerMode ? styles.collapsed : '',
    isDrawerMode ? styles.drawer : '',
    isDrawerMode && !collapsed ? styles.drawerOpen : '',
  ].filter(Boolean).join(' ');

  return (
    <>
      {isDrawerMode && !collapsed && (
        <div
          className={styles.overlay}
          onClick={onToggle}
          aria-hidden="true"
        />
      )}
      <nav
        ref={navRef}
        className={sidebarClass}
        aria-label="Primary navigation"
        aria-expanded={!collapsed}
      >
        <div className={styles.header}>
          <span className={styles.brand} aria-hidden={collapsed && !isDrawerMode}>
            Field Service
          </span>
          <button
            type="button"
            className={styles.collapseBtn}
            onClick={onToggle}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            aria-controls="sidebar-nav-list"
          >
            {collapsed && !isDrawerMode ? '›' : '‹'}
          </button>
        </div>

        <ul
          id="sidebar-nav-list"
          className={styles.navList}
          role="list"
        >
          {navItems.map((item) => {
            const isActive = currentPath.startsWith(item.path);
            return (
              <li key={item.path} role="listitem">
                <a
                  href={item.path}
                  className={[styles.navItem, isActive ? styles.navItemActive : ''].filter(Boolean).join(' ')}
                  onClick={(e) => {
                    e.preventDefault();
                    handleNav(item.path);
                  }}
                  aria-label={collapsed && !isDrawerMode ? item.label : undefined}
                  aria-current={isActive ? 'page' : undefined}
                >
                  <span className={styles.icon} aria-hidden="true">
                    {ICON_MAP[item.icon] ?? '•'}
                  </span>
                  {(!collapsed || isDrawerMode) && (
                    <span className={styles.label}>{item.label}</span>
                  )}
                </a>
              </li>
            );
          })}
        </ul>
      </nav>
    </>
  );
}
