/**
 * SitesPage — admin screen for site reference data.
 * Follows the same vertical-slice pattern as CustomersPage.
 *
 * @module features/admin/SitesPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, DetailDrawer, FormField,
  Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listSites, createSite, updateSite, deactivateSite } from '../../api/referenceData.js';
import { newAttemptKey } from '../../lib/idempotency.js';

import { PaginationBar }  from './PaginationBar.jsx';
import { ErrorSummary }   from './ErrorSummary.jsx';
import styles from './admin.module.css';

const KNOWN_FIELDS = ['customerId', 'name', 'address'];
const ALLOWED_SORTS = new Set(['name', 'createdAt']);

const COLUMNS = [
  { key: 'name',         header: 'Site name',   sortable: true  },
  { key: 'customerName', header: 'Customer',    sortable: false },
  { key: 'address',      header: 'Address',     sortable: false },
  { key: 'active',       header: 'Active',      sortable: false,
    render: (v) => v ? 'Active' : 'Inactive' },
];

const EMPTY_FORM = { customerId: '', name: '', address: '' };

/**
 * @param {{ roles?: string[] }} props
 */
export function SitesPage({ roles = [] }) {
  const isAdmin = roles.some((r) => ['ADMIN', 'MANAGER'].includes(r));
  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [mutationError, setMutationError] = useState(null);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, setFilter,
  } = usePagedQuery({
    queryKey: ['sites'],
    queryFn: (s) => listSites({ page: s.page, size: s.pageSize, sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined }),
    allowedSorts: ALLOWED_SORTS,
  });

  const { errorsFor, summaryErrors, traceId } = useFieldErrors(mutationError, KNOWN_FIELDS);

  const mutation = useMutation({
    mutationFn: (values) =>
      editing ? updateSite(editing.id, values) : createSite(values, { idempotencyKey: newAttemptKey() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['sites'] });
      closeDrawer();
    },
    onError: (err) => setMutationError(err),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id) => deactivateSite(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['sites'] }),
  });

  function openCreate() {
    setEditing(null); setForm(EMPTY_FORM); setMutationError(null); setDrawerOpen(true);
  }
  function openEdit(row) {
    setEditing(row);
    setForm({ customerId: row.customerId ?? '', name: row.name ?? '', address: row.address ?? '' });
    setMutationError(null); setDrawerOpen(true);
  }
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false); setEditing(null); setMutationError(null);
  }, []);

  if (isLoading) return <LoadingState />;
  if (isError) return <ErrorState description={error?.message} />;

  const emptyNode = state.filters.search
    ? <EmptyState title="No results" description={`No sites match "${state.filters.search}".`} />
    : <EmptyState title="No sites" description="Add your first site to get started." />;

  return (
    <div className={styles.page}>
      <PageHeader title="Sites">
        {isAdmin && <Button onClick={openCreate} aria-haspopup="dialog">Add Site</Button>}
      </PageHeader>

      <div className={styles.toolbar}>
        <input type="search" className={styles.searchInput} placeholder="Search sites…"
          aria-label="Search sites" value={state.filters.search ?? ''}
          onChange={(e) => setFilter('search', e.target.value || undefined)} />
      </div>

      <DataTable columns={COLUMNS} data={data} rowKey={(r) => r.id} sort={state.sort} onSort={setSort}
        emptyState={emptyNode}
        rowActions={isAdmin ? (row) => (
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
            <Button variant="ghost" onClick={() => deactivateMutation.mutate(row.id)}
              aria-label={`Deactivate site ${row.name}`}>Deactivate</Button>
          </div>
        ) : undefined} />

      <PaginationBar page={pageMeta.number} totalPages={pageMeta.totalPages}
        totalElements={pageMeta.totalElements} onPageChange={setPage} />

      <DetailDrawer title={editing ? 'Edit Site' : 'Add Site'} open={drawerOpen} onClose={closeDrawer}>
        <form onSubmit={(e) => { e.preventDefault(); setMutationError(null); mutation.mutate(form); }} noValidate>
          <div className={styles.formGrid}>
            <ErrorSummary errors={summaryErrors} traceId={traceId} />
            <FormField label="Customer ID" required errors={errorsFor('customerId')}>
              <input type="text" value={form.customerId}
                onChange={(e) => setForm((p) => ({ ...p, customerId: e.target.value }))} required />
            </FormField>
            <FormField label="Site name" required errors={errorsFor('name')}>
              <input type="text" value={form.name}
                onChange={(e) => setForm((p) => ({ ...p, name: e.target.value }))} required />
            </FormField>
            <FormField label="Address" errors={errorsFor('address')}>
              <textarea value={form.address} rows={3}
                onChange={(e) => setForm((p) => ({ ...p, address: e.target.value }))} />
            </FormField>
          </div>
          <div className={styles.formActions}>
            <Button type="button" variant="ghost" onClick={closeDrawer}>Cancel</Button>
            <Button type="submit" loading={mutation.isPending}>
              {editing ? 'Save changes' : 'Create site'}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  );
}
