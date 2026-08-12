/**
 * @vitest-environment jsdom
 */

import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import AuditSearchPage from './AuditSearchPage.jsx';
import {
  revisionPageFixture,
  emptyRevisionPageFixture,
  revisionDetailFixture,
  forbiddenFixture,
  invalidEntityTypeFixture,
} from '../../../mocks/handlers/auditHandlers.js';

// ── Test helpers ──────────────────────────────────────────────────────────────────────────

/**
 * Replaces global fetch with a spy returning the given fixture on the next call.
 *
 * @param {{ status: number, body: unknown, headers?: Record<string, string> }} fixture
 */
function mockFetch(fixture) {
  global.fetch = vi.fn().mockResolvedValue({
    status: fixture.status,
    ok: fixture.status >= 200 && fixture.status < 300,
    json: async () => fixture.body,
    text: async () => (typeof fixture.body === 'string'
      ? fixture.body : JSON.stringify(fixture.body)),
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  delete global.fetch;
});

// ── Tests ─────────────────────────────────────────────────────────────────────────────────

describe('AuditSearchPage', () => {

  describe('loading state', () => {
    it('shows loading indicator on initial mount', () => {
      // Fetch never resolves — keeps the component in loading state.
      global.fetch = vi.fn(() => new Promise(() => {}));
      render(<AuditSearchPage />);
      expect(screen.getByRole('status', { name: /loading audit revisions/i })).toBeInTheDocument();
    });
  });

  describe('empty state', () => {
    it('shows empty state when no revisions are returned', async () => {
      mockFetch(emptyRevisionPageFixture());
      render(<AuditSearchPage />);
      await waitFor(() =>
        expect(screen.getByRole('status', { name: /no audit revisions found/i })).toBeInTheDocument()
      );
    });
  });

  describe('permission-denied state', () => {
    it('shows permission denied message on 403 response', async () => {
      mockFetch(forbiddenFixture());
      render(<AuditSearchPage />);
      await waitFor(() =>
        expect(screen.getByRole('alert', { name: /permission denied/i })).toBeInTheDocument()
      );
    });
  });

  describe('ready state', () => {
    beforeEach(() => {
      mockFetch(revisionPageFixture({ size: 3 }));
    });

    it('renders revision table with correct column headers', async () => {
      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getByRole('grid', { name: /audit revision history/i })).toBeInTheDocument());
      const table = screen.getByRole('grid', { name: /audit revision history/i });
      expect(within(table).getByText('Rev #')).toBeInTheDocument();
      expect(within(table).getByText('Timestamp')).toBeInTheDocument();
      expect(within(table).getByText('Entity type')).toBeInTheDocument();
      expect(within(table).getByText('Change type')).toBeInTheDocument();
    });

    it('renders 3 data rows', async () => {
      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(4)); // 1 header + 3 data
    });

    it('shows ADD badge for first revision', async () => {
      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getByText('ADD')).toBeInTheDocument());
    });
  });

  describe('filter bar', () => {
    it('renders entity type filter with all allowed options', async () => {
      mockFetch(revisionPageFixture());
      render(<AuditSearchPage />);
      const select = screen.getByRole('combobox', { name: /filter by entity type/i });
      expect(select).toBeInTheDocument();
      expect(within(select).getByText('Work Order')).toBeInTheDocument();
      expect(within(select).getByText('Site')).toBeInTheDocument();
      expect(within(select).getByText('User')).toBeInTheDocument();
    });

    it('renders actor ID input', () => {
      global.fetch = vi.fn(() => new Promise(() => {}));
      render(<AuditSearchPage />);
      expect(screen.getByRole('textbox', { name: /filter by actor user id/i })).toBeInTheDocument();
    });
  });

  describe('density toggle', () => {
    it('comfortable density button is pressed by default', async () => {
      mockFetch(revisionPageFixture());
      render(<AuditSearchPage />);
      const btn = screen.getByRole('button', { name: /comfortable density/i });
      expect(btn).toHaveAttribute('aria-pressed', 'true');
    });

    it('switches to compact density on button click', async () => {
      mockFetch(revisionPageFixture());
      render(<AuditSearchPage />);
      const compact = screen.getByRole('button', { name: /compact density/i });
      fireEvent.click(compact);
      expect(compact).toHaveAttribute('aria-pressed', 'true');
    });
  });

  describe('revision detail drawer', () => {
    it('opens drawer on row click and shows field diff', async () => {
      const detailFetch = vi.fn()
        .mockResolvedValueOnce({
          status: 200, ok: true,
          json: async () => revisionPageFixture({ size: 1 }).body,
          text: async () => JSON.stringify(revisionPageFixture({ size: 1 }).body),
        })
        .mockResolvedValueOnce({
          status: 200, ok: true,
          json: async () => revisionDetailFixture().body,
          text: async () => JSON.stringify(revisionDetailFixture().body),
        });
      global.fetch = detailFetch;

      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(2));

      const rows = screen.getAllByRole('row');
      fireEvent.click(rows[1]); // first data row
      await waitFor(() => expect(screen.getByRole('dialog', { name: /revision detail/i })).toBeInTheDocument());
      expect(screen.getByRole('grid', { name: /field-level before and after diff/i })).toBeInTheDocument();
    });

    it('closes drawer on Esc key', async () => {
      const detailFetch = vi.fn()
        .mockResolvedValueOnce({
          status: 200, ok: true,
          json: async () => revisionPageFixture({ size: 1 }).body,
          text: async () => '',
        })
        .mockResolvedValueOnce({
          status: 200, ok: true,
          json: async () => revisionDetailFixture().body,
          text: async () => '',
        });
      global.fetch = detailFetch;

      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(2));
      fireEvent.click(screen.getAllByRole('row')[1]);
      await waitFor(() => expect(screen.getByRole('dialog')).toBeInTheDocument());
      fireEvent.keyDown(window, { key: 'Escape' });
      await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    });
  });

  describe('export', () => {
    it('export button is present and labelled', async () => {
      mockFetch(revisionPageFixture());
      render(<AuditSearchPage />);
      await waitFor(() =>
        expect(screen.getByRole('button', { name: /export audit revisions as CSV/i })).toBeInTheDocument()
      );
    });
  });

  describe('keyboard accessibility', () => {
    it('table rows are focusable via Tab', async () => {
      mockFetch(revisionPageFixture({ size: 2 }));
      render(<AuditSearchPage />);
      await waitFor(() => expect(screen.getAllByRole('row').length).toBeGreaterThanOrEqual(3));
      const rows = screen.getAllByRole('row');
      // Data rows should have tabIndex 0.
      expect(rows[1]).toHaveAttribute('tabindex', '0');
    });
  });
});
