/**
 * TechniciansPage — admin screen for technician management.
 *
 * @module features/admin/TechniciansPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, DetailDrawer, FormField,
  Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listTechnicians, createTechnician, deactivateTechnician } from '../../api/workforce.js';
import { newAttemptKey } from '../../lib/idempotency.js';

import { PaginationBar } from './PaginationBar.jsx';
import { ErrorSummary }  from './ErrorSummary.jsx';
import styles from './admin.module.css';

const KNOWN_FIELDS = ['userId'];
const ALLOWED_SORTS = new Set(['displayName', 'createdAt']);

const COLUMNS = [
  { key: 'displayName', header: 'Name',   sortable: true  },
  { key: 'email',       header: 'Email',  sortable: false },
  { key: 'active',      header: 'Active', sortable: false,
    render: (v) => v ? 'Active' : 'Inactive' },
];

const EMPTY_FORM = { userId: '' };

/**
 * @param {{ roles?: string[] }} props
 */
export function TechniciansPage({ roles = [] }) {
  const isAdmin = roles.some((r) => ['ADMIN', 'MANAGER'].includes(r));
  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [form, setForm] = useState(EMPTY_FORM);
  const [mutationError, setMutationError] = useState(null);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, setFilter,
  } = usePagedQuery({
    queryKey: ['technicians'],
    queryFn: (s) => listTechnicians({ page: s.page, size: s.pageSize,
      sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined }),
    allowedSorts: ALLOWED_SORTS,
  });

  const { errorsFor, summaryErrors, traceId } = useFieldErrors(mutationError, KNOWN_FIELDS);

  const mutation = useMutation({
    mutationFn: (values) => createTechnician(values, { idempotencyKey: newAttemptKey() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['technicians'] });
      closeDrawer();
    },
    onError: (err) => setMutationError(err),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id) => deactivateTechnician(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['technicians'] }),
  });

  function openCreate() {
    setForm(EMPTY_FORM); setMutationError(null); setDrawerOpen(true);
  }
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false); setMutationError(null);
  }, []);

  if (isLoading) return <LoadingState />;
  if (isError) return <ErrorState description={error?.message} />;

  const emptyNode = state.filters.search
    ? <EmptyState title="No results" description={`No technicians match "${state.filters.search}".`} />
    : <EmptyState title="No technicians" description="Add your first technician to get started." />;

  return (
    <div className={styles.page}>
      <PageHeader title="Technicians">
        {isAdmin && <Button onClick={openCreate} aria-haspopup="dialog">Add Technician</Button>}
      </PageHeader>

      <div className={styles.toolbar}>
        <input type="search" className={styles.searchInput} placeholder="Search technicians…"
          aria-label="Search technicians" value={state.filters.search ?? ''}
          onChange={(e) => setFilter('search', e.target.value || undefined)} />
      </div>

      <DataTable columns={COLUMNS} data={data} rowKey={(r) => r.id} sort={state.sort} onSort={setSort}
        emptyState={emptyNode}
        rowActions={isAdmin ? (row) => (
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button variant="ghost" onClick={() => deactivateMutation.mutate(row.id)}
              aria-label={`Deactivate ${row.displayName}`}>Deactivate</Button>
          </div>
        ) : undefined} />

      <PaginationBar page={pageMeta.number} totalPages={pageMeta.totalPages}
        totalElements={pageMeta.totalElements} onPageChange={setPage} />

      <DetailDrawer title="Add Technician" open={drawerOpen} onClose={closeDrawer}>
        <form onSubmit={(e) => { e.preventDefault(); setMutationError(null); mutation.mutate(form); }} noValidate>
          <div className={styles.formGrid}>
            <ErrorSummary errors={summaryErrors} traceId={traceId} />
            <FormField label="User ID" required errors={errorsFor('userId')}
              help="The existing app user account to link as a technician">
              <input type="text" value={form.userId}
                onChange={(e) => setForm({ userId: e.target.value })} required />
            </FormField>
          </div>
          <div className={styles.formActions}>
            <Button type="button" variant="ghost" onClick={closeDrawer}>Cancel</Button>
            <Button type="submit" loading={mutation.isPending}>Create technician</Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  );
}
