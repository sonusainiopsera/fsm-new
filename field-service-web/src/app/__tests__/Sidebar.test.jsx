import React from 'react';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { Sidebar } from '../Sidebar/Sidebar.jsx';
import { AuthContext } from '../AuthContext.js';
import { useSidebarCollapse } from '../Sidebar/useSidebarCollapse.js';
import dispatcherToken from '../../mocks/fixtures/tokens/dispatcher.json';
import technicianToken from '../../mocks/fixtures/tokens/technician.json';
import managerToken from '../../mocks/fixtures/tokens/manager.json';

function SidebarWithCollapse({ token, currentPath }) {
  const { collapsed, toggle, isDrawerMode } = useSidebarCollapse();
  return (
    <AuthContext.Provider value={{ token, setToken: vi.fn(), clearToken: vi.fn(), isAuthenticated: token !== null }}>
      <Sidebar
        collapsed={collapsed}
        isDrawerMode={isDrawerMode}
        onToggle={toggle}
        currentPath={currentPath}
      />
    </AuthContext.Provider>
  );
}

beforeEach(() => {
  localStorage.clear();
  Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 1280 });
  window.dispatchEvent(new Event('resize'));
});

describe('Sidebar — navigation derivation', () => {
  it('renders Dispatch Board for DISPATCHER token', () => {
    render(<SidebarWithCollapse token={dispatcherToken} />);
    expect(screen.getByText('Dispatch Board')).toBeInTheDocument();
  });

  it('renders no dispatcher entries for TECHNICIAN token', () => {
    render(<SidebarWithCollapse token={technicianToken} />);
    expect(screen.queryByText('Dispatch Board')).not.toBeInTheDocument();
    expect(screen.getByText('My Jobs')).toBeInTheDocument();
  });

  it('renders no technician entries for MANAGER token', () => {
    render(<SidebarWithCollapse token={managerToken} />);
    expect(screen.queryByText('My Jobs')).not.toBeInTheDocument();
    expect(screen.getByText('Operations')).toBeInTheDocument();
  });

  it('renders empty nav for null token (unauthenticated)', () => {
    render(<SidebarWithCollapse token={null} />);
    expect(screen.queryByText('Dispatch Board')).not.toBeInTheDocument();
    expect(screen.queryByText('My Jobs')).not.toBeInTheDocument();
  });
});

describe('Sidebar — collapse persistence', () => {
  it('toggles to collapsed state on collapse button click', () => {
    render(<SidebarWithCollapse token={dispatcherToken} />);
    const btn = screen.getByRole('button', { name: /collapse sidebar/i });
    fireEvent.click(btn);
    expect(localStorage.getItem('fsvc_sidebar_collapsed')).toBe('true');
  });

  it('restores collapsed state from localStorage', () => {
    localStorage.setItem('fsvc_sidebar_collapsed', 'true');
    render(<SidebarWithCollapse token={dispatcherToken} />);
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument();
  });
});

describe('Sidebar — accessibility', () => {
  it('has a nav landmark with an accessible name', () => {
    render(<SidebarWithCollapse token={dispatcherToken} />);
    expect(screen.getByRole('navigation', { name: 'Primary navigation' })).toBeInTheDocument();
  });

  it('marks active nav item with aria-current="page"', () => {
    render(<SidebarWithCollapse token={dispatcherToken} currentPath="/dispatch" />);
    const activeLink = screen.getByRole('link', { current: 'page' });
    expect(activeLink).toBeInTheDocument();
  });
});

describe('Sidebar — responsive / drawer mode', () => {
  it('shows drawer mode structure below 768px breakpoint', () => {
    act(() => {
      Object.defineProperty(window, 'innerWidth', { writable: true, configurable: true, value: 400 });
      window.dispatchEvent(new Event('resize'));
    });

    render(<SidebarWithCollapse token={dispatcherToken} />);
    expect(screen.getByRole('navigation', { name: 'Primary navigation' })).toBeInTheDocument();
  });
});
