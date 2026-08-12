/**
 * @fileoverview CreateWorkOrderModal — guided work order creation form.
 *
 * AC-1: Focus-trapped dialog, closes on Escape with unsaved-changes guard,
 *       restores focus to the invoking control on close.
 * AC-2: Dependent selects — customer restricts sites, site restricts assets.
 *       Changing a parent clears dependents so a mismatched combination
 *       cannot reach the server.
 * AC-3: Deadline preview sourced from the SLA policy endpoint; never hard-coded.
 * AC-4: Server 400 fieldErrors mapped onto the corresponding form fields.
 * AC-5: Idempotency-Key generated once per modal open, reused on retry.
 *       Submit control is disabled while in flight.
 * AC-6: On success the modal closes and the board is invalidated.
 * AC-9: 422 SLA_POLICY_MISSING renders an explicit actionable message.
 * AC-10: All fields labelled, aria-modal dialog, axe-clean.
 *
 * @module features/workorders/components/CreateWorkOrderModal
 */
import { useReducer, useCallback, useRef, useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Modal, FormField, Button } from '../../../components/index.js'
import { generateAttemptKey } from '../../../lib/idempotency.js'
import { useCreateWorkOrder, useSlaPolicyByPriority, mapCreateError } from '../api/useCreateWorkOrder.js'
import { request } from '../../../api/http.js'

// ── Constants ─────────────────────────────────────────────────────────────────

const PRIORITIES = ['LOW', 'MEDIUM', 'HIGH', 'CRITICAL']
const TITLE_MAX = 120
const FAULT_MAX = 2000

// ── Form state reducer ────────────────────────────────────────────────────────

/**
 * @typedef {{
 *   customerId: string,
 *   siteId: string,
 *   assetId: string,
 *   title: string,
 *   faultDescription: string,
 *   priority: string,
 *   requiredCertificationTypeCodes: string,
 *   expectedPartSkus: string
 * }} FormState
 */

const INITIAL_FORM = {
  customerId: '',
  siteId: '',
  assetId: '',
  title: '',
  faultDescription: '',
  priority: '',
  requiredCertificationTypeCodes: '',
  expectedPartSkus: '',
}

function formReducer(state, action) {
  switch (action.type) {
    case 'SET_FIELD':
      return { ...state, [action.field]: action.value }
    case 'SET_CUSTOMER':
      // Clear dependent fields
      return { ...state, customerId: action.value, siteId: '', assetId: '' }
    case 'SET_SITE':
      // Clear dependent asset
      return { ...state, siteId: action.value, assetId: '' }
    case 'RESET':
      return INITIAL_FORM
    default:
      return state
  }
}

// ── Reference data hooks ──────────────────────────────────────────────────────

function useCustomers() {
  return useQuery({
    queryKey: ['refdata', 'customers'],
    queryFn: () => request('/customers'),
    staleTime: 5 * 60 * 1000,
  })
}

function useSites(customerId) {
  return useQuery({
    queryKey: ['refdata', 'sites', customerId],
    queryFn: () => request(`/sites${customerId ? `?customerId=${customerId}` : ''}`),
    enabled: Boolean(customerId),
    staleTime: 5 * 60 * 1000,
  })
}

function useAssets(siteId) {
  return useQuery({
    queryKey: ['refdata', 'assets', siteId],
    queryFn: () => request(`/assets${siteId ? `?siteId=${siteId}` : ''}`),
    enabled: Boolean(siteId),
    staleTime: 5 * 60 * 1000,
  })
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/**
 * Adds a UTC offset to the current time and formats as a human-readable preview.
 * @param {number} mins
 * @returns {string}
 */
function previewDeadline(mins) {
  if (!mins) return '—'
  const d = new Date(Date.now() + mins * 60 * 1000)
  return d.toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' })
}

/**
 * Client-side validation — usability aid only (server remains authoritative).
 * @param {FormState} form
 * @returns {Record<string, string[]>}
 */
function validateForm(form) {
  const errors = {}
  if (!form.customerId) errors.customerId = ['Customer is required']
  if (!form.siteId) errors.siteId = ['Site is required']
  if (!form.title.trim()) errors.title = ['Title is required']
  else if (form.title.length > TITLE_MAX) errors.title = [`Title must not exceed ${TITLE_MAX} characters`]
  if (!form.faultDescription.trim()) errors.faultDescription = ['Fault description is required']
  else if (form.faultDescription.length > FAULT_MAX) errors.faultDescription = [`Description must not exceed ${FAULT_MAX} characters`]
  if (!form.priority) errors.priority = ['Priority is required']
  return errors
}

const hasErrors = (errs) => Object.keys(errs).length > 0

// ── Component ─────────────────────────────────────────────────────────────────

/**
 * @param {{
 *   open: boolean,
 *   onClose: () => void,
 *   onCreated?: (wo: object) => void
 * }} props
 */
export function CreateWorkOrderModal({ open, onClose, onCreated }) {
  const [form, dispatch] = useReducer(formReducer, INITIAL_FORM)
  const [clientErrors, setClientErrors] = useState({})
  const [serverError, setServerError] = useState(null)
  const [idempotencyKey, setIdempotencyKey] = useState(() => generateAttemptKey())
  const [created, setCreated] = useState(null)

  // Reset state when modal opens
  useEffect(() => {
    if (open) {
      dispatch({ type: 'RESET' })
      setClientErrors({})
      setServerError(null)
      setIdempotencyKey(generateAttemptKey())
      setCreated(null)
    }
  }, [open])

  // Reference data
  const { data: customersData } = useCustomers()
  const { data: sitesData } = useSites(form.customerId)
  const { data: assetsData } = useAssets(form.siteId)

  const customers = customersData?.data ?? []
  const sites = sitesData?.data ?? []
  const assets = assetsData?.data ?? []

  // SLA policy preview
  const { data: policy, isLoading: policyLoading } = useSlaPolicyByPriority(form.priority || null)

  const mutation = useCreateWorkOrder()

  const isDirty = form.customerId || form.siteId || form.title || form.faultDescription || form.priority

  const handleEscape = useCallback(() => {
    if (isDirty && !created) {
      if (!window.confirm('You have unsaved changes. Close anyway?')) return
    }
    onClose()
  }, [isDirty, created, onClose])

  const handleSubmit = useCallback(async (e) => {
    e.preventDefault()
    setServerError(null)

    // Client-side validation
    const errs = validateForm(form)
    if (hasErrors(errs)) {
      setClientErrors(errs)
      return
    }
    setClientErrors({})

    const payload = {
      customerId: form.customerId,
      siteId: form.siteId,
      assetId: form.assetId || undefined,
      title: form.title.trim(),
      faultDescription: form.faultDescription.trim(),
      priority: form.priority,
      requiredCertificationTypeCodes: form.requiredCertificationTypeCodes
        ? form.requiredCertificationTypeCodes.split(',').map(s => s.trim()).filter(Boolean)
        : [],
      expectedPartSkus: form.expectedPartSkus
        ? form.expectedPartSkus.split(',').map(s => s.trim()).filter(Boolean)
        : [],
      idempotencyKey,
    }

    try {
      const result = await mutation.mutateAsync(payload)
      setCreated(result)
      onCreated?.(result)
    } catch (rawErr) {
      const mapped = mapCreateError(rawErr)
      // Map field errors back onto client error state
      if (mapped.variant === 'field_errors' && mapped.fieldErrors) {
        setClientErrors(prev => ({
          ...prev,
          ...Object.fromEntries(
            Object.entries(mapped.fieldErrors).map(([k, msgs]) => [k, msgs])
          ),
        }))
      }
      setServerError(mapped)
      // Retain the same idempotency key for retries on 5xx / network
      // Advance the key only after a fresh modal open (handled in the open effect)
    }
  }, [form, idempotencyKey, mutation, onCreated])

  const selectStyle = {
    padding: 'var(--token-space-2) var(--token-space-3)',
    border: '1px solid var(--token-border-default)',
    borderRadius: 'var(--token-radius-control)',
    background: 'var(--token-surface-base)',
    color: 'var(--token-text-primary)',
    fontSize: 'var(--token-fs-14)',
    width: '100%',
  }

  const inputStyle = { ...selectStyle }
  const textareaStyle = { ...selectStyle, minHeight: '80px', resize: 'vertical' }

  const errFor = (field) => clientErrors[field] ?? []

  if (created) {
    // Success state — show confirmation with new reference and deadlines
    return (
      <Modal open={open} onClose={onClose} title="Work Order Created" size="sm">
        <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}>
          <p style={{ margin: 0, fontSize: 'var(--token-fs-15)', color: 'var(--token-text-primary)' }}>
            Work order <strong>{created.reference}</strong> has been created successfully.
          </p>
          {created.responseDueAt && (
            <p style={{ margin: 0, fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
              Response deadline: <strong>{new Date(created.responseDueAt).toLocaleString()}</strong>
            </p>
          )}
          {created.resolutionDueAt && (
            <p style={{ margin: 0, fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
              Resolution deadline: <strong>{new Date(created.resolutionDueAt).toLocaleString()}</strong>
            </p>
          )}
          <div style={{ display: 'flex', justifyContent: 'flex-end' }}>
            <Button variant="primary" onClick={onClose}>Close</Button>
          </div>
        </div>
      </Modal>
    )
  }

  return (
    <Modal open={open} onClose={handleEscape} title="Create Work Order" size="lg">
      {/* Dialog-level error summary — accessible summary for all errors at once */}
      {serverError && (
        <div
          role="alert"
          aria-live="assertive"
          style={{
            padding: 'var(--token-space-3) var(--token-space-4)',
            background: 'var(--token-danger-subtle)',
            border: '1px solid var(--token-danger-default)',
            borderRadius: 'var(--token-radius-control)',
            marginBottom: 'var(--token-space-4)',
            fontSize: 'var(--token-fs-14)',
            color: 'var(--token-danger-emphasis)',
          }}
        >
          {serverError.message}
        </div>
      )}

      <form
        onSubmit={handleSubmit}
        noValidate
        style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-4)' }}
      >
        {/* Customer */}
        <FormField label="Customer" required errors={errFor('customerId')}>
          {(props) => (
            <select
              {...props}
              value={form.customerId}
              onChange={e => dispatch({ type: 'SET_CUSTOMER', value: e.target.value })}
              style={selectStyle}
            >
              <option value="">— Select customer —</option>
              {customers.filter(c => c.active).map(c => (
                <option key={c.id} value={c.id}>{c.name}</option>
              ))}
            </select>
          )}
        </FormField>

        {/* Site — disabled until a customer is selected */}
        <FormField label="Site" required errors={errFor('siteId')} helpText={!form.customerId ? 'Select a customer first' : undefined}>
          {(props) => (
            <select
              {...props}
              value={form.siteId}
              onChange={e => dispatch({ type: 'SET_SITE', value: e.target.value })}
              disabled={!form.customerId}
              style={{ ...selectStyle, opacity: !form.customerId ? 0.5 : 1 }}
            >
              <option value="">— Select site —</option>
              {sites.filter(s => s.active).map(s => (
                <option key={s.id} value={s.id}>{s.name}</option>
              ))}
            </select>
          )}
        </FormField>

        {/* Asset — optional, disabled until a site is selected */}
        <FormField label="Asset" errors={errFor('assetId')} helpText={!form.siteId ? 'Select a site first (optional)' : 'Optional'}>
          {(props) => (
            <select
              {...props}
              value={form.assetId}
              onChange={e => dispatch({ type: 'SET_FIELD', field: 'assetId', value: e.target.value })}
              disabled={!form.siteId}
              style={{ ...selectStyle, opacity: !form.siteId ? 0.5 : 1 }}
            >
              <option value="">— No specific asset —</option>
              {assets.filter(a => a.active).map(a => (
                <option key={a.id} value={a.id}>{a.assetTag} — {a.description ?? a.assetTypeCode}</option>
              ))}
            </select>
          )}
        </FormField>

        {/* Title */}
        <FormField label="Title" required errors={errFor('title')}>
          {(props) => (
            <div style={{ position: 'relative' }}>
              <input
                {...props}
                type="text"
                value={form.title}
                maxLength={TITLE_MAX}
                onChange={e => dispatch({ type: 'SET_FIELD', field: 'title', value: e.target.value })}
                placeholder="Brief summary of the fault"
                style={inputStyle}
              />
              <span style={{ position: 'absolute', right: 8, bottom: 6, fontSize: 'var(--token-fs-12)', color: 'var(--token-text-tertiary)' }}>
                {form.title.length}/{TITLE_MAX}
              </span>
            </div>
          )}
        </FormField>

        {/* Fault description */}
        <FormField label="Fault Description" required errors={errFor('faultDescription')}>
          {(props) => (
            <div>
              <textarea
                {...props}
                value={form.faultDescription}
                maxLength={FAULT_MAX}
                onChange={e => dispatch({ type: 'SET_FIELD', field: 'faultDescription', value: e.target.value })}
                placeholder="Describe the fault in detail…"
                style={textareaStyle}
              />
              <span style={{ display: 'block', textAlign: 'right', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-tertiary)', marginTop: 'var(--token-space-1)' }}>
                {form.faultDescription.length}/{FAULT_MAX}
              </span>
            </div>
          )}
        </FormField>

        {/* Priority */}
        <FormField label="Priority" required errors={errFor('priority')}>
          {(props) => (
            <select
              {...props}
              value={form.priority}
              onChange={e => dispatch({ type: 'SET_FIELD', field: 'priority', value: e.target.value })}
              style={selectStyle}
            >
              <option value="">— Select priority —</option>
              {PRIORITIES.map(p => (
                <option key={p} value={p}>{p}</option>
              ))}
            </select>
          )}
        </FormField>

        {/* SLA deadline preview — shown when priority is selected */}
        {form.priority && (
          <div
            aria-live="polite"
            style={{
              padding: 'var(--token-space-3) var(--token-space-4)',
              background: 'var(--token-surface-card)',
              border: '1px solid var(--token-border-default)',
              borderRadius: 'var(--token-radius-control)',
              fontSize: 'var(--token-fs-13)',
              color: 'var(--token-text-secondary)',
            }}
          >
            {policyLoading ? (
              <span>Loading SLA policy…</span>
            ) : policy ? (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--token-space-1)' }}>
                <strong style={{ color: 'var(--token-text-primary)', fontSize: 'var(--token-fs-14)' }}>
                  Estimated deadlines ({form.priority})
                </strong>
                <span>Response by: <strong>{previewDeadline(policy.responseMins)}</strong> ({policy.responseMins} min)</span>
                <span>Resolution by: <strong>{previewDeadline(policy.resolutionMins)}</strong> ({policy.resolutionMins} min)</span>
                <span style={{ fontSize: 'var(--token-fs-12)', color: 'var(--token-text-tertiary)' }}>
                  Authoritative deadlines are set by the server on submission.
                </span>
              </div>
            ) : (
              <span style={{ color: 'var(--token-danger-emphasis)' }}>
                ⚠ No SLA policy is configured for this priority tier. Submission will be rejected.
              </span>
            )}
          </div>
        )}

        {/* Required certifications (optional) */}
        <FormField label="Required Certification Codes" helpText="Comma-separated certification type codes, e.g. GAS_SAFE, ELECTRICAL_18TH" errors={errFor('requiredCertificationTypeCodes')}>
          {(props) => (
            <input
              {...props}
              type="text"
              value={form.requiredCertificationTypeCodes}
              onChange={e => dispatch({ type: 'SET_FIELD', field: 'requiredCertificationTypeCodes', value: e.target.value })}
              placeholder="e.g. GAS_SAFE, ELECTRICAL_18TH"
              style={inputStyle}
            />
          )}
        </FormField>

        {/* Expected parts (optional) */}
        <FormField label="Expected Parts (SKUs)" helpText="Comma-separated part SKUs" errors={errFor('expectedPartSkus')}>
          {(props) => (
            <input
              {...props}
              type="text"
              value={form.expectedPartSkus}
              onChange={e => dispatch({ type: 'SET_FIELD', field: 'expectedPartSkus', value: e.target.value })}
              placeholder="e.g. PART-001, PART-007"
              style={inputStyle}
            />
          )}
        </FormField>

        {/* Actions */}
        <div
          style={{
            display: 'flex',
            justifyContent: 'flex-end',
            gap: 'var(--token-space-3)',
            paddingTop: 'var(--token-space-2)',
            borderTop: '1px solid var(--token-border-default)',
          }}
        >
          <Button type="button" variant="ghost" onClick={handleEscape} disabled={mutation.isPending}>
            Cancel
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={mutation.isPending}
            aria-busy={mutation.isPending}
          >
            {mutation.isPending ? 'Creating…' : 'Create Work Order'}
          </Button>
        </div>
      </form>
    </Modal>
  )
}
