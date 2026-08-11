/**
 * Unit tests for Sidebar collapse persistence and responsive breakpoint.
 * These tests mock react-router-dom since Sidebar uses NavLink.
 */
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, fireEvent, within } from '@testing-library/react'

// Mock react-router-dom before importing Sidebar
vi.mock('react-router-dom', () => ({
  NavLink: ({ to, children, className, ...rest }) => {
    const cls = typeof className === 'function' ? className({ isActive: false }) : className
    return (
      <a href={to} className={cls} {...rest}>
        {children}
      </a>
    )
  },
}))

import { Sidebar } from './Sidebar.jsx'

const SAMPLE_NAV = [
  { key: 'dispatch-board', path: '/dispatch', label: 'Dispatch Board', icon: 'grid', allowedRoles: ['DISPATCHER'], surface: 'dispatch' },
  { key: 'work-orders', path: '/dispatch/work-orders', label: 'Work Orders', icon: 'clipboard', allowedRoles: ['DISPATCHER'], surface: 'dispatch' },
]

function setViewportWidth(width) {
  Object.defineProperty(window, 'innerWidth', { value: width, writable: true, configurable: true })
  // Simulate matchMedia
  Object.defineProperty(window, 'matchMedia', {
    writable: true,
    configurable: true,
    value: vi.fn().mockImplementation((query) => ({
      matches: width < 768 && query.includes('max-width'),
      media: query,
      onchange: null,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    })),
  })
}

describe('Sidebar — rendering', () => {
  beforeEach(() => {
    localStorage.clear()
    setViewportWidth(1280)
  })

  it('renders navigation landmark', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    expect(screen.getByRole('navigation')).toBeInTheDocument()
  })

  it('renders all provided nav items', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    expect(screen.getByText('Dispatch Board')).toBeInTheDocument()
    expect(screen.getByText('Work Orders')).toBeInTheDocument()
  })

  it('renders no items when navItems is empty', () => {
    render(<Sidebar navItems={[]} />)
    const nav = screen.getByRole('navigation')
    expect(within(nav).queryAllByRole('listitem')).toHaveLength(0)
  })

  it('uses provided aria-label on navigation landmark', () => {
    render(<Sidebar navItems={SAMPLE_NAV} aria-label="Main navigation" />)
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).toBeInTheDocument()
  })
})

describe('Sidebar — collapse state (desktop)', () => {
  beforeEach(() => {
    localStorage.clear()
    setViewportWidth(1280)
  })

  it('defaults to expanded (data-collapsed=false)', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    expect(nav.getAttribute('data-collapsed')).toBe('false')
  })

  it('restores collapsed=true from localStorage', () => {
    localStorage.setItem('fs-sidebar-collapsed', 'true')
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    expect(nav.getAttribute('data-collapsed')).toBe('true')
  })

  it('toggles to collapsed when toggle button is clicked', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    const toggle = screen.getByRole('button', { name: /collapse navigation/i })
    fireEvent.click(toggle)
    expect(nav.getAttribute('data-collapsed')).toBe('true')
  })

  it('persists collapsed=true to localStorage after toggle', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const toggle = screen.getByRole('button', { name: /collapse navigation/i })
    fireEvent.click(toggle)
    expect(localStorage.getItem('fs-sidebar-collapsed')).toBe('true')
  })

  it('toggles back to expanded on second click', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    const toggle = screen.getByRole('button', { name: /collapse navigation/i })
    fireEvent.click(toggle)
    expect(nav.getAttribute('data-collapsed')).toBe('true')
    const expandToggle = screen.getByRole('button', { name: /expand navigation/i })
    fireEvent.click(expandToggle)
    expect(nav.getAttribute('data-collapsed')).toBe('false')
  })

  it('persists expanded state after two toggles', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const toggle = screen.getByRole('button', { name: /collapse navigation/i })
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('button', { name: /expand navigation/i }))
    expect(localStorage.getItem('fs-sidebar-collapsed')).toBe('false')
  })
})

describe('Sidebar — icon-only accessibility when collapsed', () => {
  beforeEach(() => {
    localStorage.setItem('fs-sidebar-collapsed', 'true')
    setViewportWidth(1280)
  })

  it('adds aria-label to nav links when collapsed', () => {
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const links = screen.getAllByRole('link')
    links.forEach(link => {
      expect(link.getAttribute('aria-label')).toBeTruthy()
    })
  })
})

describe('Sidebar — responsive breakpoint (768px)', () => {
  beforeEach(() => {
    localStorage.clear()
  })

  it('at 480px viewport, sidebar has no data-mobile-open=undefined', () => {
    setViewportWidth(480)
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    // Mobile: data-mobile-open attribute should be present
    expect(nav.hasAttribute('data-mobile-open')).toBe(true)
  })

  it('at 1280px viewport, no data-mobile-open attribute', () => {
    setViewportWidth(1280)
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    expect(nav.getAttribute('data-mobile-open')).toBeNull()
  })

  it('at 480px, collapse preference from localStorage does not affect data-collapsed', () => {
    // Stored preference is 'true' but at mobile the off-canvas wins
    localStorage.setItem('fs-sidebar-collapsed', 'true')
    setViewportWidth(480)
    render(<Sidebar navItems={SAMPLE_NAV} />)
    const nav = screen.getByRole('navigation')
    // On mobile, collapsed is always false (drawer mode takes over)
    expect(nav.getAttribute('data-collapsed')).toBe('false')
  })
})
