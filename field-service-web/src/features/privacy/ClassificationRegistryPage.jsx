/**
 * ClassificationRegistryPage — read and edit the data classification registry.
 *
 * Paginates at server maximum 50. Tier is displayed as a Chip (neutral variant with
 * label). Edit opens a Modal with versioned PUT; 409 returns a conflict notice.
 *
 * SECURITY NOTE: routes are guarded by PRIVACY_ADMIN / ADMIN. A 403 from the API
 * renders PermissionDeniedState via the GlobalExceptionHandler.
 *
 * @module features/privacy/ClassificationRegistryPage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient, useQuery } from '@tanstack/react-query';

import {
  PageHeader, DataTable, Modal, FormField,
  Button, EmptyState, LoadingState, ErrorState, PermissionDeniedState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listClassifications, updateClassification } from '../../api/privacyAdmin.js';

import { PaginationBar } from '../admin/PaginationBar.jsx';
import { ErrorSummary }  from '../admin/ErrorSummary.jsx';
import styles from '../admin/admin.module.css';

const TIER_ORDER = ['PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED'];

const TIER_LABEL = {
  PUBLIC:       'Public',
  INTERNAL:     'Internal',
  CONFIDENTIAL: 'Confidential',
  RESTRICTED:   'Restricted',
};

const COLUMNS = [
  { key: 'module',     header: 'Module',    sortable: true },
  { key: 'entityName', header: 'Entity',    sortable: true },
  { key: 'fieldName',  header: 'Field',     sortable: true,
    render: (v) => v ?? <em className={styles.mutedText}>entity-level</em> },
  { key: 'tier',       header: 'Tier',      sortable: true,
    render: (v) => <span className={styles[`tier-${v?.toLowerCase()}`]}>{TIER_LABEL[v] ?? v}</span> },
  { key: 'handlingNotes', header: 'Handling Notes', sortable: false,
    render: (v) => v ? <span className={styles.truncate} title={v}>{v}</span> : <em className={styles.mutedText}>—</em> },
];

const EMPTY_EDIT = { tier: 'CONFIDENTIAL', lawfulBasisNote: '', handlingNotes: '', version: 0 };

/**
 * @param {{ roles?: string[] }} props
 */
export function ClassificationRegistryPage({ roles = [] }) {
  const canEdit = roles.some((r) => ['PRIVACY_ADMIN', 'ADMIN'].includes(r));

  const queryClient = useQueryClient();
  const [editRow, setEditRow] = useState(/** @type {object | null} */ (null));
  const [editForm, setEditForm] = useState(EMPTY_EDIT);
  const [conflictMsg, setConflictMsg] = useState('');
  const [mutationError, setMutationError] = useState(null);

  const { fieldErrors, setFieldErrors, clearFieldErrors, getFieldError } = useFieldErrors([
    'tier', 'lawfulBasisNote', 'handlingNotes',
  ]);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, refetch,
  } = usePagedQuery({
    queryKey: ['privacy', 'classifications'],
    queryFn:  (s) => listClassifications({
      page: s.page,
      size: s.pageSize,
      sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined,
    }),
    defaultPageSize: 20,
    allowedSorts: new Set(['module', 'entityName', 'fieldName', 'tier', 'updatedAt']),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, body }) => updateClassification(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['privacy', 'classifications'] });
      setEditRow(null);
      setConflictMsg('');
      setMutationError(null);
    },
    onError: (err) => {
      if (err?.status === 409) {
        setConflictMsg('Another user updated this row. Please close, review the latest values and try again.');
        queryClient.invalidateQueries({ queryKey: ['privacy', 'classifications'] });
        return;
      }
      if (err?.status === 403) { setMutationError({ message: 'Permission denied.' }); return; }
      if (err?.fieldErrors?.length) { setFieldErrors(err.fieldErrors); return; }
      setMutationError(err);
    },
  });

  const openEdit = useCallback((row) => {
    setEditRow(row);
    setEditForm({ tier: row.tier, lawfulBasisNote: row.lawfulBasisNote ?? '', handlingNotes: row.handlingNotes ?? '', version: row.version });
    setConflictMsg('');
    setMutationError(null);
    clearFieldErrors();
  }, [clearFieldErrors]);

  const handleSave = () => {
    if (!editRow) return;
    updateMutation.mutate({ id: editRow.id, body: editForm });
  };

  if (isLoading) return <LoadingState />;
  if (isError) {
    const status = error?.status ?? error?.statusCode;
    if (status === 403) return <PermissionDeniedState />;
    return <ErrorState onRetry={refetch} description="Could not load the classification registry." />;
  }

  const rows = data ?? [];

  return (
    <div className={styles.page}>
      <PageHeader
        title="Classification Registry"
        description="Data classification tiers and handling notes per entity or field."
      />

      <DataTable
        caption="Data classification registry"
        columns={COLUMNS}
        rows={rows}
        onSort={(field, direction) => setSort({ field, direction })}
        currentSort={state.sort}
        rowActions={canEdit ? (row) => (
          <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
        ) : undefined}
        emptySlot={<EmptyState description="No classification rows found." />}
      />

      <PaginationBar
        page={pageMeta?.number ?? 0}
        totalPages={pageMeta?.totalPages ?? 1}
        totalElements={pageMeta?.totalElements ?? 0}
        onPage={setPage}
      />

      <Modal
        open={!!editRow}
        title={`Edit — ${editRow?.entityName}${editRow?.fieldName ? `.${editRow.fieldName}` : ''}`}
        onClose={() => { setEditRow(null); setConflictMsg(''); }}
        footer={
          <>
            <Button variant="secondary" onClick={() => { setEditRow(null); setConflictMsg(''); }}>Cancel</Button>
            <Button variant="primary" onClick={handleSave} disabled={updateMutation.isPending}>
              {updateMutation.isPending ? 'Saving…' : 'Save'}
            </Button>
          </>
        }
      >
        {conflictMsg && (
          <div className={styles.conflictNotice} role="alert">
            {conflictMsg}
          </div>
        )}
        {mutationError && <ErrorSummary error={mutationError} />}

        <FormField
          label="Tier"
          id="edit-tier"
          error={getFieldError('tier')}
        >
          <select
            id="edit-tier"
            value={editForm.tier}
            onChange={(e) => setEditForm((f) => ({ ...f, tier: e.target.value }))}
            className={styles.input}
          >
            {TIER_ORDER.map((t) => (
              <option key={t} value={t}>{TIER_LABEL[t]}</option>
            ))}
          </select>
        </FormField>

        <FormField
          label="Lawful basis note"
          id="edit-lawful-basis"
          error={getFieldError('lawfulBasisNote')}
        >
          <input
            id="edit-lawful-basis"
            type="text"
            value={editForm.lawfulBasisNote}
            onChange={(e) => setEditForm((f) => ({ ...f, lawfulBasisNote: e.target.value }))}
            className={styles.input}
          />
        </FormField>

        <FormField
          label="Handling notes"
          id="edit-handling-notes"
          error={getFieldError('handlingNotes')}
        >
          <textarea
            id="edit-handling-notes"
            rows={3}
            value={editForm.handlingNotes}
            onChange={(e) => setEditForm((f) => ({ ...f, handlingNotes: e.target.value }))}
            className={styles.input}
          />
        </FormField>
      </Modal>
    </div>
  );
}
