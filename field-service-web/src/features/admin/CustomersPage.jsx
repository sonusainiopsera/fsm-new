/**
 * CustomersPage — admin screen for customer reference data.
 *
 * Vertical slice pattern: uses usePagedQuery for URL-state-synced pagination,
 * useFieldErrors for inline field error mapping, and shared primitives.
 *
 * Create / Edit flows use a DetailDrawer form.
 * Server-side 400 fieldErrors render inline beneath the matching input.
 * Unmapped errors and the traceId render in ErrorSummary above the form.
 *
 * @module features/admin/CustomersPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, DetailDrawer, FormField,
  Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listCustomers, createCustomer, updateCustomer, deactivateCustomer } from '../../api/referenceData.js';
import { newAttemptKey } from '../../lib/idempotency.js';

import { PaginationBar }  from './PaginationBar.jsx';
import { ErrorSummary }   from './ErrorSummary.jsx';
import styles from './admin.module.css';

const KNOWN_FIELDS = ['name', 'contactEmail', 'contactPhone', 'address'];
const ALLOWED_SORTS = new Set(['name', 'createdAt']);

const COLUMNS = [
  { key: 'name',         header: 'Name',          sortable: true },
  { key: 'contactEmail', header: 'Email',          sortable: false },
  { key: 'contactPhone', header: 'Phone',          sortable: false },
  { key: 'active',       header: 'Active',         sortable: false,
    render: (v) => v ? 'Active' : 'Inactive' },
];

const EMPTY_FORM = { name: '', contactEmail: '', contactPhone: '', address: '' };

/**
 * @param {{ roles?: string[] }} props
 */
export function CustomersPage({ roles = [] }) {
  const isAdmin = roles.some((r) => ['ADMIN', 'MANAGER'].includes(r));

  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState(/** @type {object | null} */ (null));
  const [form, setForm] = useState(EMPTY_FORM);
  const [mutationError, setMutationError] = useState(null);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, setFilter, refetch,
  } = usePagedQuery({
    queryKey: ['customers'],
    queryFn: (s) => listCustomers({ page: s.page, size: s.pageSize, sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined }),
    allowedSorts: ALLOWED_SORTS,
  });

  const { errorsFor, summaryErrors, traceId } = useFieldErrors(mutationError, KNOWN_FIELDS);

  const mutation = useMutation({
    mutationFn: async (values) => {
      if (editing) {
        return updateCustomer(editing.id, values);
      }
      return createCustomer(values, { idempotencyKey: newAttemptKey() });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['customers'] });
      closeDrawer();
    },
    onError: (err) => setMutationError(err),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id) => deactivateCustomer(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['customers'] }),
  });

  function openCreate() {
    setEditing(null);
    setForm(EMPTY_FORM);
    setMutationError(null);
    setDrawerOpen(true);
  }

  function openEdit(row) {
    setEditing(row);
    setForm({
      name:         row.name         ?? '',
      contactEmail: row.contactEmail ?? '',
      contactPhone: row.contactPhone ?? '',
      address:      row.address      ?? '',
    });
    setMutationError(null);
    setDrawerOpen(true);
  }

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
    setEditing(null);
    setMutationError(null);
  }, []);

  function handleSubmit(e) {
    e.preventDefault();
    setMutationError(null);
    mutation.mutate(form);
  }

  function handleField(key, value) {
    setForm((prev) => ({ ...prev, [key]: value }));
  }

  if (isLoading) return <LoadingState />;
  if (isError && error?.status === 403) return <div>You do not have permission to view customers.</div>;
  if (isError) return <ErrorState description={error?.message} />;

  const emptyNode = state.filters.search
    ? <EmptyState title="No results" description={`No customers match "${state.filters.search}".`} />
    : <EmptyState title="No customers" description="Add your first customer to get started." />;

  return (
    <div className={styles.page}>
      <PageHeader title="Customers">
        {isAdmin && (
          <Button onClick={openCreate} aria-haspopup="dialog">
            Add Customer
          </Button>
        )}
      </PageHeader>

      <div className={styles.toolbar}>
        <input
          type="search"
          className={styles.searchInput}
          placeholder="Search customers…"
          aria-label="Search customers"
          value={state.filters.search ?? ''}
          onChange={(e) => setFilter('search', e.target.value || undefined)}
        />
      </div>

      <DataTable
        columns={COLUMNS}
        data={data}
        rowKey={(r) => r.id}
        sort={state.sort}
        onSort={setSort}
        emptyState={emptyNode}
        rowActions={isAdmin ? (row) => (
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
            <Button variant="ghost" onClick={() => deactivateMutation.mutate(row.id)}
              aria-label={`Deactivate ${row.name}`}>
              Deactivate
            </Button>
          </div>
        ) : undefined}
      />

      <PaginationBar
        page={pageMeta.number}
        totalPages={pageMeta.totalPages}
        totalElements={pageMeta.totalElements}
        onPageChange={setPage}
      />

      <DetailDrawer
        title={editing ? 'Edit Customer' : 'Add Customer'}
        open={drawerOpen}
        onClose={closeDrawer}
      >
        <form onSubmit={handleSubmit} noValidate>
          <div className={styles.formGrid}>
            <ErrorSummary errors={summaryErrors} traceId={traceId} />

            <FormField label="Name" required errors={errorsFor('name')}>
              <input
                type="text"
                value={form.name}
                onChange={(e) => handleField('name', e.target.value)}
                required
              />
            </FormField>

            <FormField label="Contact email" errors={errorsFor('contactEmail')}>
              <input
                type="email"
                value={form.contactEmail}
                onChange={(e) => handleField('contactEmail', e.target.value)}
              />
            </FormField>

            <FormField label="Contact phone" errors={errorsFor('contactPhone')}>
              <input
                type="tel"
                value={form.contactPhone}
                onChange={(e) => handleField('contactPhone', e.target.value)}
              />
            </FormField>

            <FormField label="Address" errors={errorsFor('address')}>
              <textarea
                value={form.address}
                onChange={(e) => handleField('address', e.target.value)}
                rows={3}
              />
            </FormField>
          </div>

          <div className={styles.formActions}>
            <Button type="button" variant="ghost" onClick={closeDrawer}>Cancel</Button>
            <Button type="submit" loading={mutation.isPending}>
              {editing ? 'Save changes' : 'Create customer'}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  );
}
