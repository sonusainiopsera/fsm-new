/**
 * CreateWorkOrderModal — guided work-order creation form.
 *
 * Constraints (WO-131):
 * - Dependent selects: sites filtered by customer; assets filtered by site.
 * - Changing a parent clears dependent fields to prevent mismatched submission.
 * - Deadline preview from server SLA policy — never hard-coded.
 * - Idempotent submit: one key per modal open, reused on retry.
 * - Server 400 fieldErrors mapped to the correct inputs.
 * - 422 SLA_POLICY_MISSING → specific actionable message.
 * - Unsaved-changes confirmation on Escape / backdrop click.
 * - Focus-trapped, aria-modal dialog.
 *
 * @module features/workorders/components/CreateWorkOrderModal
 */

import React, { useReducer, useEffect, useCallback, useRef } from 'react';
import { useQuery } from '@tanstack/react-query';

import { Modal, FormField, Button } from '../../../components/index.js';
import { useFieldErrors } from '../../../shared/forms/useFieldErrors.js';
import { useCreateWorkOrder } from '../api/useCreateWorkOrder.js';
import { apiFetch } from '../../../api/http.js';
import { listCustomers, listSites, listAssets } from '../../../api/referenceData.js';

import styles from './CreateWorkOrderModal.module.css';

// ---- Form state ---------------------------------------------------------

const INITIAL_FORM = {
  customerId:                  '',
  siteId:                      '',
  assetId:                     '',
  faultDescription:            '',
  priority:                    '',
  requiredCertificationCodes:  [],
  expectedParts:               [],
};

const MAX_FAULT_DESC = 4000;
const MIN_FAULT_DESC = 10;

const PRIORITIES = ['URGENT', 'HIGH', 'NORMAL', 'LOW'];

/** @param {typeof INITIAL_FORM} state */
function formReducer(state, action) {
  switch (action.type) {
    case 'SET_FIELD':
      return { ...state, [action.field]: action.value };
    case 'SET_CUSTOMER':
      // Clear site and asset when customer changes.
      return { ...state, customerId: action.value, siteId: '', assetId: '' };
    case 'SET_SITE':
      // Clear asset when site changes.
      return { ...state, siteId: action.value, assetId: '' };
    case 'RESET':
      return INITIAL_FORM;
    default:
      return state;
  }
}

function isDirty(form) {
  return (
    form.customerId !== '' ||
    form.siteId !== '' ||
    form.faultDescription !== '' ||
    form.priority !== ''
  );
}

// ---- Reference data helpers ---------------------------------------------

function useCustomerOptions() {
  const { data } = useQuery({
    queryKey: ['customers-for-modal'],
    queryFn:  () => listCustomers({ size: 50 }),
    staleTime: 60_000,
  });
  return data?.data ?? [];
}

function useSiteOptions(customerId) {
  const { data } = useQuery({
    queryKey:  ['sites-for-modal', customerId],
    queryFn:   () => listSites({ customerId, size: 50 }),
    enabled:   !!customerId,
    staleTime: 60_000,
  });
  return data?.data ?? [];
}

function useAssetOptions(siteId) {
  const { data } = useQuery({
    queryKey:  ['assets-for-modal', siteId],
    queryFn:   () => listAssets({ siteId, size: 50 }),
    enabled:   !!siteId,
    staleTime: 60_000,
  });
  return data?.data ?? [];
}

function useSlaPolicyPreview(priority) {
  const { data, isError } = useQuery({
    queryKey:  ['sla-policy-preview', priority],
    queryFn:   () => apiFetch(`/sla-policies/by-priority/${priority}`),
    enabled:   !!priority,
    staleTime: 300_000,
    retry:     false,
  });
  return { policy: data ?? null, policyMissing: isError };
}

// ---- Deadline preview ---------------------------------------------------

function addMinutes(isoNow, minutes) {
  return new Date(Date.now() + minutes * 60_000).toISOString();
}

function formatDeadlinePreview(policy) {
  if (!policy) return null;
  return {
    responseDeadline:   addMinutes(null, policy.responseMinutes),
    resolutionDeadline: addMinutes(null, policy.resolutionMinutes),
  };
}

// ---- Component ----------------------------------------------------------

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   onCreated?: (workOrder: unknown) => void,
 * }} props
 */
export function CreateWorkOrderModal({ open, onClose, onCreated }) {
  const [form, dispatch]         = useReducer(formReducer, INITIAL_FORM);
  const { submit, isSubmitting, isSuccess, data: createdWo, error, reset } = useCreateWorkOrder();

  const customers  = useCustomerOptions();
  const sites      = useSiteOptions(form.customerId);
  const assets     = useAssetOptions(form.siteId);
  const { policy, policyMissing } = useSlaPolicyPreview(form.priority);

  const KNOWN_FIELDS = ['customerId', 'siteId', 'assetId', 'faultDescription', 'priority'];
  const { errorsFor, summaryErrors } = useFieldErrors(error, KNOWN_FIELDS);

  // On success: close modal and notify parent.
  useEffect(() => {
    if (!isSuccess || !createdWo) return;
    onCreated?.(createdWo);
    handleClose(true);
  }, [isSuccess, createdWo]); // eslint-disable-line react-hooks/exhaustive-deps

  // Reset form when modal opens.
  useEffect(() => {
    if (open) {
      dispatch({ type: 'RESET' });
      reset();
    }
  }, [open]); // eslint-disable-line react-hooks/exhaustive-deps

  function handleClose(skipConfirm = false) {
    if (!skipConfirm && isDirty(form)) {
      if (!window.confirm('You have unsaved changes. Close anyway?')) return;
    }
    onClose();
  }

  function handleSubmit(e) {
    e.preventDefault();

    // Client-side guard: priority must have a policy.
    if (policyMissing) return;

    const payload = {
      customerId:       form.customerId,
      siteId:           form.siteId,
      assetId:          form.assetId || null,
      faultDescription: form.faultDescription,
      priority:         form.priority,
      requiredCertificationCodes: form.requiredCertificationCodes,
      expectedParts:    form.expectedParts,
    };

    submit(payload);
  }

  const deadlinePreview = formatDeadlinePreview(policy);

  // 422 SLA_POLICY_MISSING specific message
  const slaMissingMsg =
    error?.code === 'SLA_POLICY_MISSING'
      ? 'No SLA policy is configured for this priority tier. Please choose a different priority or ask an administrator to configure the policy.'
      : null;

  const submitDisabled =
    isSubmitting ||
    !form.customerId ||
    !form.siteId ||
    !form.faultDescription ||
    !form.priority ||
    policyMissing;

  return (
    <Modal
      open={open}
      title="New Work Order"
      onClose={() => handleClose(false)}
      footer={
        <div className={styles.footer}>
          <Button type="button" variant="secondary" onClick={() => handleClose(false)} disabled={isSubmitting}>
            Cancel
          </Button>
          <Button type="submit" form="create-wo-form" disabled={submitDisabled}>
            {isSubmitting ? 'Creating…' : 'Create Work Order'}
          </Button>
        </div>
      }
    >
      {/* Error summary region for screen readers */}
      {(summaryErrors.length > 0 || slaMissingMsg) && (
        <div role="alert" aria-live="assertive" className={styles.errorSummary}>
          <ul>
            {slaMissingMsg && <li>{slaMissingMsg}</li>}
            {summaryErrors.map((msg, i) => <li key={i}>{msg}</li>)}
          </ul>
        </div>
      )}

      <form id="create-wo-form" onSubmit={handleSubmit} noValidate>
        {/* Customer */}
        <FormField label="Customer" required errors={errorsFor('customerId')}>
          <select
            value={form.customerId}
            onChange={(e) => dispatch({ type: 'SET_CUSTOMER', value: e.target.value })}
            required
          >
            <option value="">— Select customer —</option>
            {customers.map((c) => (
              <option key={c.id} value={c.id}>{c.name}</option>
            ))}
          </select>
        </FormField>

        {/* Site — dependent on customer */}
        <FormField label="Site" required errors={errorsFor('siteId')}>
          <select
            value={form.siteId}
            onChange={(e) => dispatch({ type: 'SET_SITE', value: e.target.value })}
            disabled={!form.customerId}
            required
          >
            <option value="">— Select site —</option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>{s.name}</option>
            ))}
          </select>
        </FormField>

        {/* Asset — dependent on site, optional */}
        <FormField label="Asset (optional)" errors={errorsFor('assetId')}>
          <select
            value={form.assetId}
            onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'assetId', value: e.target.value })}
            disabled={!form.siteId}
          >
            <option value="">— Select asset —</option>
            {assets.map((a) => (
              <option key={a.id} value={a.id}>{a.serialNumber ? `${a.assetType} — ${a.serialNumber}` : a.assetType}</option>
            ))}
          </select>
        </FormField>

        {/* Priority */}
        <FormField label="Priority" required errors={errorsFor('priority')}>
          <select
            value={form.priority}
            onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'priority', value: e.target.value })}
            required
          >
            <option value="">— Select priority —</option>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>{p}</option>
            ))}
          </select>
        </FormField>

        {/* SLA deadline preview */}
        {form.priority && (
          <div className={styles.deadlinePreview} aria-live="polite">
            {policyMissing ? (
              <p className={styles.policyMissing} role="alert">
                ⚠ No SLA policy configured for <strong>{form.priority}</strong>.
                Select a different priority or ask an administrator to configure the policy.
              </p>
            ) : policy ? (
              <dl className={styles.deadlineList}>
                <dt>Response deadline</dt>
                <dd>{deadlinePreview?.responseDeadline ? new Date(deadlinePreview.responseDeadline).toLocaleString() : '—'}</dd>
                <dt>Resolution deadline</dt>
                <dd>{deadlinePreview?.resolutionDeadline ? new Date(deadlinePreview.resolutionDeadline).toLocaleString() : '—'}</dd>
                <dt>Policy</dt>
                <dd>
                  {policy.responseMinutes}m response / {policy.resolutionMinutes}m resolution
                </dd>
              </dl>
            ) : (
              <p className={styles.policyLoading}>Loading policy…</p>
            )}
          </div>
        )}

        {/* Fault description */}
        <FormField
          label="Fault description"
          required
          help={`${MIN_FAULT_DESC}–${MAX_FAULT_DESC} characters.`}
          errors={errorsFor('faultDescription')}
        >
          <textarea
            value={form.faultDescription}
            onChange={(e) => dispatch({ type: 'SET_FIELD', field: 'faultDescription', value: e.target.value })}
            maxLength={MAX_FAULT_DESC}
            minLength={MIN_FAULT_DESC}
            rows={4}
            required
            aria-describedby={undefined}
          />
        </FormField>
        <div className={styles.charCount} aria-live="polite">
          {form.faultDescription.length} / {MAX_FAULT_DESC}
        </div>
      </form>
    </Modal>
  );
}
