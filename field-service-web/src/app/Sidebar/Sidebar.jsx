/**
 * @fileoverview Sidebar navigation component.
 * 240px expanded, 64px icon rail collapsed, off-canvas drawer below 768px.
 * Collapsed state persisted in localStorage (key: fs-sidebar-collapsed).
 *
 * SECURITY NOTE (A01): Role-filtered navItems are a USABILITY control only.
 * Server-side enforcement of route access remains the sole security boundary.
 */
import { useState, useEffect } from 'react'
import { NavLink } from 'react-router-dom'
import styles from './Sidebar.module.css'

const STORAGE_KEY = 'fs-sidebar-collapsed'
const MOBILE_BREAKPOINT = 768

/**
 * @param {{
 *   navItems: import('../navigation.js').NavItem[],
 *   'aria-label'?: string,
 * }} props
 */
export function Sidebar({ navItems, 'aria-label': ariaLabel = 'Primary navigation' }) {
  const [isCollapsed, setIsCollapsed] = useState(() => {
    try {
      return localStorage.getItem(STORAGE_KEY) === 'true'
    } catch {
      return false
    }
  })

  const [isMobile, setIsMobile] = useState(
    () => typeof window !== 'undefined' && window.innerWidth < MOBILE_BREAKPOINT
  )

  const [isMobileOpen, setIsMobileOpen] = useState(false)

  // Persist collapse preference for desktop viewports
  useEffect(() => {
    if (!isMobile) {
      try {
        localStorage.setItem(STORAGE_KEY, String(isCollapsed))
      } catch {
        // localStorage unavailable — collapse state is ephemeral
      }
    }
  }, [isCollapsed, isMobile])

  // Track viewport width — off-canvas wins below 768px (AC-1 constraint)
  useEffect(() => {
    if (typeof window === 'undefined') return
    const mq = window.matchMedia(`(max-width: ${MOBILE_BREAKPOINT - 1}px)`)
    const handler = (e) => {
      setIsMobile(e.matches)
      if (!e.matches) setIsMobileOpen(false) // close drawer on desktop expand
    }
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  function handleToggle() {
    if (isMobile) {
      setIsMobileOpen(o => !o)
    } else {
      setIsCollapsed(c => !c)
    }
  }

  const collapsed = isMobile ? false : isCollapsed

  return (
    <>
      {isMobile && isMobileOpen && (
        <div
          className={styles.overlay}
          aria-hidden="true"
          onClick={() => setIsMobileOpen(false)}
        />
      )}

      <nav
        aria-label={ariaLabel}
        className={styles.sidebar}
        data-collapsed={String(collapsed)}
        data-mobile-open={isMobile ? String(isMobileOpen) : undefined}
      >
        <button
          type="button"
          className={styles.collapseToggle}
          aria-expanded={!collapsed}
          aria-label={collapsed ? 'Expand navigation' : 'Collapse navigation'}
          onClick={handleToggle}
        >
          <span aria-hidden="true">{collapsed ? '›' : '‹'}</span>
        </button>

        <ul role="list" className={styles.navList}>
          {navItems.map(item => (
            <li key={item.key}>
              <NavLink
                to={item.path}
                aria-label={collapsed ? item.label : undefined}
                title={collapsed ? item.label : undefined}
                className={({ isActive }) =>
                  [styles.navItem, isActive ? styles.navItemActive : ''].filter(Boolean).join(' ')
                }
              >
                <span className={styles.icon} aria-hidden="true">
                  {ICONS[item.icon] ?? item.icon}
                </span>
                <span className={styles.label}>{item.label}</span>
              </NavLink>
            </li>
          ))}
        </ul>
      </nav>
    </>
  )
}

// Simple text icon set — replaced by an SVG sprite in a future design WO
const ICONS = {
  grid: '▦',
  clipboard: '📋',
  users: '👥',
  box: '📦',
  briefcase: '💼',
  'bar-chart': '📊',
  'file-text': '📄',
  'help-circle': '❓',
  settings: '⚙',
}
