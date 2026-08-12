/**
 * RetentionSchedulePage — view and edit retention schedule, run dry-run previews.
 *
 * Unratified rows display an explicit "Indicative — not ratified" label so they
 * are never presented as confirmed targets. Dry-run shows eligible row count,
 * oldest eligible timestamp, cut-off and disposal method; makes clear no data changed.
 *
 * @module features/privacy/RetentionSchedulePage
 */

import React, { useState, useCallback } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';

import {
  PageHeader, DataTable, Modal, FormField,
  Button, EmptyState, LoadingState, ErrorState, PermissionDeniedState,
} from '../../components/index.js';
import { usePagedQuery }  from '../../shared/hooks/usePagedQuery.js';
import { useFieldErrors } from '../../shared/forms/useFieldErrors.js';
import { listRetentionPolicies, updateRetentionPolicy, dryRunRetention } from '../../api/privacyAdmin.js';

import { PaginationBar } from '../admin/PaginationBar.jsx';
import { ErrorSummary }  from '../admin/ErrorSummary.jsx';
import styles from '../admin/admin.module.css';

const PERIOD_UNITS = ['DAYS', 'MONTHS', 'YEARS'];

const COLUMNS = [
  { key: 'dataCategory', header: 'Category', sortable: true },
  { key: 'entityName',   header: 'Entity',   sortable: false,
    render: (v) => v ?? <em className={styles.mutedText}>—</em> },
  { key: 'periodValue',  header: 'Period',   sortable: false,
    render: (v, row) => `${v} ${row.periodUnit}` },
  { key: 'disposalMethod', header: 'Disposal', sortable: false,
    render: (v) => v === 'CRYPTO_ERASE' ? 'Crypto-erase' : 'Physical delete' },
  { key: 'legalHold',   header: 'Legal hold', sortable: false,
    render: (v) => v ? <span className={styles.warningBadge}>Hold</span> : '—' },
  { key: 'ratified',    header: 'Status', sortable: false,
    render: (v, row) => {
      if (!v) return <em className={styles.indicativeBadge}>Indicative — not ratified</em>;
      if (row.enabled) return 'Active';
      return 'Inactive';
    },
  },
];

const EMPTY_EDIT = { periodValue: '', periodUnit: 'YEARS', legalHold: false, notes: '', version: 0 };

/**
 * @param {{ roles?: string[] }} props
 */
export function RetentionSchedulePage({ roles = [] }) {
  const canEdit = roles.some((r) => ['PRIVACY_ADMIN', 'ADMIN'].includes(r));

  const queryClient = useQueryClient();
  const [editRow, setEditRow] = useState(null);
  const [editForm, setEditForm] = useState(EMPTY_EDIT);
  const [conflictMsg, setConflictMsg] = useState('');
  const [mutationError, setMutationError] = useState(null);
  const [dryRunResult, setDryRunResult] = useState(/** @type {object | null} */ (null));
  const [dryRunRow, setDryRunRow] = useState(null);
  const [dryRunLoading, setDryRunLoading] = useState(false);
  const [dryRunError, setDryRunError] = useState(null);

  const { fieldErrors, setFieldErrors, clearFieldErrors, getFieldError } = useFieldErrors([
    'periodValue', 'periodUnit',
  ]);

  const {
    data, page: pageMeta, isLoading, isError, error, state,
    setPage, setSort, refetch,
  } = usePagedQuery({
    queryKey: ['privacy', 'retention-policies'],
    queryFn:  (s) => listRetentionPolicies({
      page: s.page,
      size: s.pageSize,
      sort: s.sort ? `${s.sort.field},${s.sort.direction}` : undefined,
    }),
    defaultPageSize: 20,
    allowedSorts: new Set(['dataCategory', 'periodValue', 'updatedAt']),
  });

  const updateMutation = useMutation({
    mutationFn: ({ id, body }) => updateRetentionPolicy(id, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['privacy', 'retention-policies'] });
      setEditRow(null);
      setConflictMsg('');
      setMutationError(null);
    },
    onError: (err) => {
      if (err?.status === 409) {
        setConflictMsg('Another user updated this row. Please close, review the latest values and try again.');
        queryClient.invalidateQueries({ queryKey: ['privacy', 'retention-policies'] });
        return;
      }
      if (err?.status === 403) { setMutationError({ message: 'Permission denied.' }); return; }
      if (err?.fieldErrors?.length) { setFieldErrors(err.fieldErrors); return; }
      setMutationError(err);
    },
  });

  const openEdit = useCallback((row) => {
    setEditRow(row);
    setEditForm({
      periodValue: String(row.periodValue),
      periodUnit: row.periodUnit,
      legalHold: row.legalHold,
      notes: row.notes ?? '',
      version: row.version,
    });
    setConflictMsg('');
    setMutationError(null);
    clearFieldErrors();
  }, [clearFieldErrors]);

  const handleSave = () => {
    if (!editRow) return;
    const periodValue = parseInt(editForm.periodValue, 10);
    if (isNaN(periodValue) || periodValue < 1) {
      setFieldErrors([{ field: 'periodValue', message: 'Period must be a positive integer.' }]);
      return;
    }
    updateMutation.mutate({
      id: editRow.id,
      body: { periodValue, periodUnit: editForm.periodUnit, legalHold: editForm.legalHold, notes: editForm.notes, version: editForm.version },
    });
  };

  const runDryRun = useCallback(async (row) => {
    setDryRunRow(row);
    setDryRunResult(null);
    setDryRunError(null);
    setDryRunLoading(true);
    try {
      const result = await dryRunRetention(row.id);
      setDryRunResult(result);
    } catch (err) {
      setDryRunError(err?.message ?? 'Dry-run failed.');
    } finally {
      setDryRunLoading(false);
    }
  }, []);

  if (isLoading) return <LoadingState />;
  if (isError) {
    const status = error?.status ?? error?.statusCode;
    if (status === 403) return <PermissionDeniedState />;
    return <ErrorState onRetry={refetch} description="Could not load retention policies." />;
  }

  const rows = data ?? [];

  return (
    <div className={styles.page}>
      <PageHeader
        title="Retention Schedule"
        description="Per-category data retention periods and disposal rules."
      />

      <DataTable
        caption="Retention schedule"
        columns={COLUMNS}
        rows={rows}
        onSort={(field, direction) => setSort({ field, direction })}
        currentSort={state.sort}
        rowActions={canEdit ? (row) => (
          <>
            <Button variant="ghost" onClick={() => openEdit(row)}>Edit</Button>
            <Button variant="ghost" onClick={() => runDryRun(row)}>Dry run</Button>
          </>
        ) : undefined}
        emptySlot={<EmptyState description="No retention policies configured." />}
      />

      <PaginationBar
        page={pageMeta?.number ?? 0}
        totalPages={pageMeta?.totalPages ?? 1}
        totalElements={pageMeta?.totalElements ?? 0}
        onPage={setPage}
      />

      {/* Edit dialog */}
      <Modal
        open={!!editRow}
        title={`Edit — ${editRow?.dataCategory}`}
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
        {conflictMsg && <div className={styles.conflictNotice} role="alert">{conflictMsg}</div>}
        {mutationError && <ErrorSummary error={mutationError} />}
        {editRow && !editRow.ratified && (
          <div className={styles.infoNotice} role="note">
            This policy is indicative and not yet ratified. Changes here do not constitute a ratified target.
          </div>
        )}

        <div className={styles.formRow}>
          <FormField label="Period" id="edit-period-value" error={getFieldError('periodValue')}>
            <input
              id="edit-period-value"
              type="number"
              min={1}
              value={editForm.periodValue}
              onChange={(e) => setEditForm((f) => ({ ...f, periodValue: e.target.value }))}
              className={styles.input}
            />
          </FormField>
          <FormField label="Unit" id="edit-period-unit" error={getFieldError('periodUnit')}>
            <select
              id="edit-period-unit"
              value={editForm.periodUnit}
              onChange={(e) => setEditForm((f) => ({ ...f, periodUnit: e.target.value }))}
              className={styles.input}
            >
              {PERIOD_UNITS.map((u) => <option key={u} value={u}>{u}</option>)}
            </select>
          </FormField>
        </div>

        <FormField label="" id="edit-legal-hold">
          <label className={styles.checkboxLabel}>
            <input
              id="edit-legal-hold"
              type="checkbox"
              checked={editForm.legalHold}
              onChange={(e) => setEditForm((f) => ({ ...f, legalHold: e.target.checked }))}
            />
            <span>Legal hold active</span>
          </label>
        </FormField>

        <FormField label="Notes" id="edit-notes">
          <textarea
            id="edit-notes"
            rows={2}
            value={editForm.notes}
            onChange={(e) => setEditForm((f) => ({ ...f, notes: e.target.value }))}
            className={styles.input}
          />
        </FormField>
      </Modal>

      {/* Dry-run dialog */}
      <Modal
        open={!!dryRunRow}
        title={`Dry Run — ${dryRunRow?.dataCategory}`}
        onClose={() => { setDryRunRow(null); setDryRunResult(null); setDryRunError(null); }}
        footer={
          <Button variant="secondary" onClick={() => { setDryRunRow(null); setDryRunResult(null); setDryRunError(null); }}>
            Close
          </Button>
        }
      >
        <p className={styles.dryRunNotice}>
          No data will be changed. This preview shows what would be affected if the purge ran now.
        </p>
        {dryRunLoading && <LoadingState />}
        {dryRunError && <p className={styles.errorText} role="alert">{dryRunError}</p>}
        {dryRunResult && !dryRunLoading && (
          <dl className={styles.dryRunReport}>
            <dt>Eligible rows</dt>
            <dd className={styles.tabularFigure}>{dryRunResult.eligibleCount ?? 0}</dd>
            <dt>Cut-off date</dt>
            <dd>{dryRunResult.cutoffAt ? new Date(dryRunResult.cutoffAt).toLocaleString() : '—'}</dd>
            <dt>Oldest eligible</dt>
            <dd>{dryRunResult.oldestEligibleAt ? new Date(dryRunResult.oldestEligibleAt).toLocaleString() : '—'}</dd>
            <dt>Disposal method</dt>
            <dd>{dryRunRow?.disposalMethod === 'CRYPTO_ERASE' ? 'Crypto-erase' : 'Physical delete'}</dd>
          </dl>
        )}
        {dryRunResult && !dryRunLoading && (dryRunResult.eligibleCount === 0) && (
          <EmptyState description="No rows are currently eligible for disposal under this policy." />
        )}
      </Modal>
    </div>
  );
}
