/**
 * CertificationTypesPage — admin screen for certification type registry.
 *
 * @module features/admin/CertificationTypesPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, DetailDrawer, FormField,
  Button, Chip, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import {
  listCertificationTypes, createCertificationType,
  updateCertificationType, deactivateCertificationType,
} from '../../api/workforce.js';
import { newAttemptKey } from '../../lib/idempotency.js';

import { PaginationBar } from './PaginationBar.jsx';
import { ErrorSummary }  from './ErrorSummary.jsx';
import styles from './admin.module.css';

const KNOWN_FIELDS = ['code', 'displayName', 'regulated', 'defaultValidityMonths'];
const ALLOWED_SORTS = new Set(['code', 'displayName']);

const COLUMNS = [
  { key: 'code',                 header: 'Code',             sortable: true  },
  { key: 'displayName',          header: 'Display name',     sortable: true  },
  { key: 'regulated',            header: 'Regulated',        sortable: false,
    render: (v) => v
      ? <Chip variant="error"   label="Regulated" icon="shield" />
      : <Chip variant="neutral" label="Advisory"  /> },
  { key: 'defaultValidityMonths', header: 'Validity (mo.)', sortable: false,
    render: (v) => v != null ? v : '—' },
  { key: 'active', header: 'Active', sortable: false,
    render: (v) => v ? 'Active' : 'Inactive' },
];

const EMPTY_FORM = { code: '', displayName: '', regulated: false, defaultValidityMonths: '' };

/**
 * @param {{ roles?: string[] }} props
 */
export function CertificationTypesPage({ roles = [] }) {
  const isAdmin = roles.some((r) => ['ADMIN', 'MANAGER'].includes(r));
  const queryClient = useQueryClient();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [editing, setEditing] = useState(null);
  const [form, setForm] = useState(EMPTY_FORM);
  const [mutationError, setMutationError] = useState(null);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort,
  } = usePagedQuery({
    queryKey: ['certification-types'],
    queryFn: (s) => listCertificationTypes({ page: s.page, size: s.pageSize,
      sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined }),
    allowedSorts: ALLOWED_SORTS,
  });

  const { errorsFor, summaryErrors, traceId } = useFieldErrors(mutationError, KNOWN_FIELDS);

  const mutation = useMutation({
    mutationFn: (values) => {
      const body = {
        ...values,
        defaultValidityMonths: values.defaultValidityMonths !== '' ? Number(values.defaultValidityMonths) : null,
      };
      return editing
        ? updateCertificationType(editing.id, body)
        : createCertificationType(body, { idempotencyKey: newAttemptKey() });
    },
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['certification-types'] });
      closeDrawer();
    },
    onError: (err) => setMutationError(err),
  });

  const deactivateMutation = useMutation({
    mutationFn: (id) => deactivateCertificationType(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['certification-types'] }),
  });

  function openCreate() {
    setEditing(null); setForm(EMPTY_FORM); setMutationError(null); setDrawerOpen(true);
  }
  function openEdit(row) {
    setEditing(row);
    setForm({
      code: row.code ?? '',
      displayName: row.displayName ?? '',
      regulated: !!row.regulated,
      defaultValidityMonths: row.defaultValidityMonths != null ? String(row.defaultValidityMonths) : '',
    });
    setMutationError(null); setDrawerOpen(true);
  }
  const closeDrawer = useCallback(() => {
    setDrawerOpen(false); setEditing(null); setMutationError(null);
  }, []);

  if (isLoading) return <LoadingState />;
  if (isError) return <ErrorState description={error?.message} />;

  const emptyNode = <EmptyState title="No certification types" description="Add certification types to enable certification tracking." />;

  return (
    <div className={styles.page}>
      <PageHeader title="Certification Types">
        {isAdmin && <Button onClick={openCreate} aria-haspopup="dialog">Add Type</Button>}
      </PageHeader>

      <DataTable columns={COLUMNS} data={data} rowKey={(r) => r.id} sort={state.sort} onSort={setSort}
        emptyState={emptyNode}
        rowActions={isAdmin ? (row) => (
          <div style={{ display: 'flex', gap: 'var(--space-2)' }}>
            <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
            {row.active && (
              <Button variant="ghost" onClick={() => deactivateMutation.mutate(row.id)}
                aria-label={`Deactivate ${row.code}`}>Deactivate</Button>
            )}
          </div>
        ) : undefined} />

      <PaginationBar page={pageMeta.number} totalPages={pageMeta.totalPages}
        totalElements={pageMeta.totalElements} onPageChange={setPage} />

      <DetailDrawer title={editing ? 'Edit Certification Type' : 'Add Certification Type'}
        open={drawerOpen} onClose={closeDrawer}>
        <form onSubmit={(e) => { e.preventDefault(); setMutationError(null); mutation.mutate(form); }} noValidate>
          <div className={styles.formGrid}>
            <ErrorSummary errors={summaryErrors} traceId={traceId} />

            <FormField label="Code" required errors={errorsFor('code')}
              help="Uppercase letters, numbers and underscores only (e.g. GAS_SAFE)">
              <input type="text" value={form.code} disabled={!!editing}
                onChange={(e) => setForm((p) => ({ ...p, code: e.target.value.toUpperCase() }))}
                required pattern="[A-Z0-9_]{2,50}" />
            </FormField>

            <FormField label="Display name" required errors={errorsFor('displayName')}>
              <input type="text" value={form.displayName}
                onChange={(e) => setForm((p) => ({ ...p, displayName: e.target.value }))} required />
            </FormField>

            <FormField label="Default validity (months)" errors={errorsFor('defaultValidityMonths')}
              help="Leave blank for perpetual (no expiry)">
              <input type="number" min={1} max={600} value={form.defaultValidityMonths}
                onChange={(e) => setForm((p) => ({ ...p, defaultValidityMonths: e.target.value }))} />
            </FormField>

            <FormField label="Regulated" errors={errorsFor('regulated')}
              help="Regulated types cause a hard 422 refusal on dispatch; advisory types generate warnings only">
              <label style={{ display: 'flex', gap: 'var(--space-2)', alignItems: 'center', minHeight: '44px' }}>
                <input type="checkbox" checked={form.regulated}
                  onChange={(e) => setForm((p) => ({ ...p, regulated: e.target.checked }))} />
                This certification type is regulated
              </label>
            </FormField>
          </div>

          <div className={styles.formActions}>
            <Button type="button" variant="ghost" onClick={closeDrawer}>Cancel</Button>
            <Button type="submit" loading={mutation.isPending}>
              {editing ? 'Save changes' : 'Create type'}
            </Button>
          </div>
        </form>
      </DetailDrawer>
    </div>
  );
}
