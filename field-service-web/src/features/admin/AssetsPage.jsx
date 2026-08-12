/**
 * AssetsPage — admin screen for asset reference data.
 *
 * @module features/admin/AssetsPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, DetailDrawer, FormField,
  Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listAssets, createAsset, updateAsset, deactivateAsset } from '../../api/referenceData.js';
import { newAttemptKey } from '../../lib/idempotency.js';

import { PaginationBar } from './PaginationBar.jsx';
import { ErrorSummary }  from './ErrorSummary.jsx';
import styles from './admin.module.css';

const KNOWN_FIELDS = ['siteId', 'assetType', 'serialNumber', 'model'];
const ALLOWED_SORTS = new Set(['assetType', 'createdAt']);

const COLUMNS = [
  { key: 'assetType',    header: 'Type',          sortable: true  },
  { key: 'siteName',     header: 'Site',          sortable: false },
  { key: 'serialNumber', header: 'Serial number', sortable: false },
  { key: 'model',        header: 'Model',         sortable: false },
  { key: 'active',       header: 'Active',        sortable: false,
    render: (v) => v ? 'Active' : 'Inactive' },
];

const EMPTY_FORM = { siteId: '', assetType: '', serialNumber: '', model: '' };

/**
 * @param {{ roles?: string[] }} props
 */
export function AssetsPage({ roles = [] }) {
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
    queryKey: ['assets'],
    queryFn: (s) => listAssets({ page: s.page, size: s.pageSize, sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined }),
    allowedSorts: ALLOWED_SORTS,
  });

  const { errorsFor, summaryErrors, traceId } = useFieldErrors(mutationError, KNOWN_FIELDS);

  const mutation = useMutation({
    mutationFn: (values) =>
      editing ? updateAsset(editing.id, values) : createAsset(values, { idempotencyKey: newAttemptKey() }),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['assets'] });
      closeDrawer();
    },
    onError: (err) => setMutationError(err),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id) => deactivateAsset(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['assets'] }),
  });

  function openCreate() {
    setEditing(null); setForm(EMPTY_FORM); setMutationError(null); setDrawerOpen(true);
  }
  function openEdit(row) {
    setEditing(row);
    setForm({ siteId: row.siteId ?? '', assetType: row.assetType ?? '',
              serialNumber: row.serialNumber ?? '', model: row.model ?? '' });
    setMutationError(null); setDrawerOpen(true);
  }
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false); setEditing(null); setMutationError(null);
  }, []);

  if (isLoading) return <LoadingState />;
  if (isError) return <ErrorState description={error?.message} />;

  const emptyNode = state.filters.search
    ? <EmptyState title="No results" description={`No assets match "${state.filters.search}".`} />
    : <EmptyState title="No assets" description="Add your first asset to get started." />;

  return (
    <div className={styles.page}>
      <PageHeader title="Assets">
        {isAdmin && <Button onClick={openCreate} aria-haspopup="dialog">Add Asset</Button>}
      </PageHeader>

      <div className={styles.toolbar}>
        <input type="search" className={styles.searchInput} placeholder="Search assets…"
          aria-label="Search assets" value={state.filters.search ?? ''}
          onChange={(e) => setFilter('search', e.target.value || undefined)} />
      </div>

      <DataTable columns={COLUMNS} data={data} rowKey={(r) => r.id} sort={state.sort} onSort={setSort}
        emptyState={emptyNode}
        rowActions={isAdmin ? (row) => (
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
            <Button variant="ghost" onClick={() => deactivateMutation.mutate(row.id)}
              aria-label={`Deactivate asset ${row.assetType}`}>Deactivate</Button>
          </div>
        ) : undefined} />

      <PaginationBar page={pageMeta.number} totalPages={pageMeta.totalPages}
        totalElements={pageMeta.totalElements} onPageChange={setPage} />

      <DetailDrawer title={editing ? 'Edit Asset' : 'Add Asset'} open={drawerOpen} onClose={closeDrawer}>
        <form onSubmit={(e) => { e.preventDefault(); setMutationError(null); mutation.mutate(form); }} noValidate>
          <div className={styles.formGrid}>
            <ErrorSummary errors={summaryErrors} traceId={traceId} />
            <FormField label="Site ID" required errors={errorsFor('siteId')}>
              <input type="text" value={form.siteId}
                onChange={(e) => setForm((p) => ({ ...p, siteId: e.target.value }))} required />
            </FormField>
            <FormField label="Asset type" required errors={errorsFor('assetType')}>
              <input type="text" value={form.assetType}
                onChange={(e) => setForm((p) => ({ ...p, assetType: e.target.value }))} required />
            </FormField>
            <FormField label="Serial number" errors={errorsFor('serialNumber')}>
              <input type="text" value={form.serialNumber}
                onChange={(e) => setForm((p) => ({ ...p, serialNumber: e.target.value }))} />
            </FormField>
            <FormField label="Model" errors={errorsFor('model')}>
              <input type="text" value={form.model}
                onChange={(e) => setForm((p) => ({ ...p, model: e.target.value }))} />
            </FormField>
          </div>
          <div className={styles.formActions}>
            <Button type="button" variant="ghost" onClick={closeDrawer}>Cancel</Button>
            <Button type="submit" loading={mutation.isPending}>
              {editing ? 'Save changes' : 'Create asset'}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  );
}
