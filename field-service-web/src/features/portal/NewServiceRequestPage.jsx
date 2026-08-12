/**
 * @fileoverview NewServiceRequestPage — customer portal service request submission form.
 *
 * Generates a stable Idempotency-Key once per form instance (useRef) so retries
 * reuse the same key and cannot create duplicate work orders (AC-3).
 *
 * Server-side 400 field errors are mapped inline to the originating FormField (AC-1).
 * 429 shows a plain-language retry message with the Retry-After duration (AC-1).
 *
 * Character counter for faultDescription mirrors the server 4–2000 character rule.
 */

import { useRef, useState, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import {
  PageHeader, FormField, Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js'
import { useSites, useSiteAssets, useSubmitServiceRequest } from '../../api/portalClient.js'

const FAULT_MIN = 4
const FAULT_MAX = 2000

const CONTACT_PREFERENCES = [
  { value: 'EMAIL', label: 'Email' },
  { value: 'PHONE', label: 'Phone call' },
]

/**
 * @param {{ message?: string, traceId?: string }} error
 * @returns {string}
 */
function humaniseError(error) {
  if (!error) return 'Something went wrong. Please try again.'
  if (error.status === 429) {
    const wait = error.retryAfter ? `Please wait ${error.retryAfter} seconds before trying again.` : 'Please wait a moment before trying again.'
    return `Too many requests. ${wait}`
  }
  if (error.status === 404) return 'We could not find the selected site or asset. Please try again.'
  return error.message ?? 'Something went wrong. Please try again.'
}

export default function NewServiceRequestPage() {
  const navigate = useNavigate()

  // Stable idempotency key for this form instance — generated once on mount
  const idempotencyKeyRef = useRef(
    typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
      ? crypto.randomUUID()
      : Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
  )

  const [siteId, setSiteId] = useState('')
  const [assetId, setAssetId] = useState('')
  const [faultDescription, setFaultDescription] = useState('')
  const [contactPreference, setContactPreference] = useState('EMAIL')
  const [fieldValidation, setFieldValidation] = useState({})

  const { data: sites, isLoading: sitesLoading, isError: sitesError } = useSites()
  const { data: assets, isLoading: assetsLoading } = useSiteAssets(siteId || null)
  const mutation = useSubmitServiceRequest(idempotencyKeyRef.current)

  const serverFieldErrors = mutation.error?.fieldErrors ?? []

  const validate = useCallback(() => {
    const errors = {}
    if (!siteId) errors.siteId = ['Please select a site.']
    if (!faultDescription.trim()) {
      errors.faultDescription = ['Please describe the fault.']
    } else if (faultDescription.length < FAULT_MIN) {
      errors.faultDescription = [`Description must be at least ${FAULT_MIN} characters.`]
    } else if (faultDescription.length > FAULT_MAX) {
      errors.faultDescription = [`Description must be ${FAULT_MAX} characters or fewer.`]
    }
    if (!contactPreference) errors.contactPreference = ['Please choose a contact preference.']
    setFieldValidation(errors)
    return Object.keys(errors).length === 0
  }, [siteId, faultDescription, contactPreference])

  const handleSubmit = useCallback(async (e) => {
    e.preventDefault()
    if (!validate()) return

    const payload = {
      siteId,
      faultDescription,
      contactPreference,
    }
    if (assetId) payload.assetId = assetId

    try {
      const result = await mutation.mutateAsync(payload)
      const workOrderId = result?.data?.workOrderId
      if (workOrderId) {
        navigate(`/portal/requests/${workOrderId}/status`, {
          state: { reference: result.data.reference },
        })
      }
    } catch {
      // Error displayed from mutation.error below
    }
  }, [siteId, assetId, faultDescription, contactPreference, mutation, navigate, validate])

  if (sitesLoading) {
    return <LoadingState />
  }

  if (sitesError) {
    return <ErrorState message="We could not load your sites. Please refresh the page to try again." />
  }

  const siteList = sites ?? []
  const assetList = assets ?? []
  const faultLen = faultDescription.length
  const faultTooLong = faultLen > FAULT_MAX
  const isSubmitting = mutation.isPending

  return (
    <main
      style={{
        maxWidth: 640,
        margin: '0 auto',
        padding: 'var(--token-space-8)',
        fontFamily: 'var(--token-family-base)',
      }}
    >
      <PageHeader title="Request a Service" />

      {siteList.length === 0 && (
        <EmptyState message="No sites are registered to your account. Please contact us to have a site added." />
      )}

      {siteList.length > 0 && (
        <form onSubmit={handleSubmit} noValidate>
          <div
            style={{
              display: 'flex',
              flexDirection: 'column',
              gap: 'var(--token-space-6)',
            }}
          >
            {/* Site selection */}
            <FormField
              label="Site"
              required
              helpText="Select the site where the issue is located."
              errors={fieldValidation.siteId}
              fieldErrors={serverFieldErrors.filter(fe => fe.field === 'siteId')}
            >
              {(inputProps) => (
                <select
                  {...inputProps}
                  value={siteId}
                  onChange={(e) => {
                    setSiteId(e.target.value)
                    setAssetId('')
                  }}
                  disabled={isSubmitting}
                  style={{
                    width: '100%',
                    height: 'var(--token-space-12)',
                    padding: '0 var(--token-space-4)',
                    fontSize: 'var(--token-fs-16)',
                    border: '1px solid var(--token-border-default)',
                    borderRadius: 'var(--token-radius-sm)',
                    background: 'var(--token-surface-0)',
                    color: 'var(--token-text-primary)',
                    minHeight: '48px',
                  }}
                >
                  <option value="">Select a site…</option>
                  {siteList.map((site) => (
                    <option key={site.id} value={site.id}>
                      {site.name}
                    </option>
                  ))}
                </select>
              )}
            </FormField>

            {/* Asset selection (optional) */}
            {siteId && (
              <FormField
                label="Equipment (optional)"
                helpText="Select the specific item that needs attention, if applicable."
              >
                {(inputProps) => (
                  <select
                    {...inputProps}
                    value={assetId}
                    onChange={(e) => setAssetId(e.target.value)}
                    disabled={isSubmitting || assetsLoading}
                    style={{
                      width: '100%',
                      height: 'var(--token-space-12)',
                      padding: '0 var(--token-space-4)',
                      fontSize: 'var(--token-fs-16)',
                      border: '1px solid var(--token-border-default)',
                      borderRadius: 'var(--token-radius-sm)',
                      background: 'var(--token-surface-0)',
                      color: 'var(--token-text-primary)',
                      minHeight: '48px',
                    }}
                  >
                    <option value="">No specific equipment</option>
                    {assetList.map((asset) => (
                      <option key={asset.id} value={asset.id}>
                        {asset.name}
                      </option>
                    ))}
                  </select>
                )}
              </FormField>
            )}

            {/* Fault description */}
            <FormField
              label="Describe the problem"
              required
              helpText={`Please describe what is happening. ${FAULT_MIN}–${FAULT_MAX} characters.`}
              errors={fieldValidation.faultDescription}
              fieldErrors={serverFieldErrors.filter(fe => fe.field === 'faultDescription')}
            >
              {(inputProps) => (
                <div style={{ position: 'relative' }}>
                  <textarea
                    {...inputProps}
                    value={faultDescription}
                    onChange={(e) => setFaultDescription(e.target.value)}
                    rows={5}
                    maxLength={FAULT_MAX + 1}
                    disabled={isSubmitting}
                    style={{
                      width: '100%',
                      padding: 'var(--token-space-3) var(--token-space-4)',
                      fontSize: 'var(--token-fs-16)',
                      lineHeight: 1.5,
                      border: `1px solid ${faultTooLong ? 'var(--token-danger-default)' : 'var(--token-border-default)'}`,
                      borderRadius: 'var(--token-radius-sm)',
                      background: 'var(--token-surface-0)',
                      color: 'var(--token-text-primary)',
                      resize: 'vertical',
                      boxSizing: 'border-box',
                    }}
                  />
                  <div
                    aria-live="polite"
                    aria-atomic="true"
                    style={{
                      textAlign: 'right',
                      fontSize: 'var(--token-fs-13)',
                      color: faultTooLong ? 'var(--token-danger-emphasis)' : 'var(--token-text-secondary)',
                      marginTop: 'var(--token-space-1)',
                    }}
                  >
                    <span className="sr-only">Characters used: </span>
                    {faultLen} / {FAULT_MAX}
                  </div>
                </div>
              )}
            </FormField>

            {/* Contact preference */}
            <FormField
              label="Preferred contact method"
              required
              errors={fieldValidation.contactPreference}
              fieldErrors={serverFieldErrors.filter(fe => fe.field === 'contactPreference')}
            >
              {(inputProps) => (
                <div
                  role="group"
                  aria-describedby={inputProps['aria-describedby']}
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 'var(--token-space-3)',
                  }}
                >
                  {CONTACT_PREFERENCES.map(({ value, label }) => (
                    <label
                      key={value}
                      style={{
                        display: 'flex',
                        alignItems: 'center',
                        gap: 'var(--token-space-3)',
                        fontSize: 'var(--token-fs-16)',
                        color: 'var(--token-text-primary)',
                        cursor: isSubmitting ? 'default' : 'pointer',
                        minHeight: '48px',
                      }}
                    >
                      <input
                        type="radio"
                        name="contactPreference"
                        value={value}
                        checked={contactPreference === value}
                        onChange={() => setContactPreference(value)}
                        disabled={isSubmitting}
                        style={{ width: 20, height: 20, cursor: 'inherit' }}
                      />
                      {label}
                    </label>
                  ))}
                </div>
              )}
            </FormField>

            {/* General mutation error (non-field errors) */}
            {mutation.isError && (
              <div
                role="alert"
                aria-live="assertive"
                style={{
                  padding: 'var(--token-space-4)',
                  background: 'var(--token-danger-subtle)',
                  border: '1px solid var(--token-danger-default)',
                  borderRadius: 'var(--token-radius-sm)',
                  fontSize: 'var(--token-fs-15)',
                  color: 'var(--token-text-primary)',
                }}
              >
                {humaniseError(mutation.error)}
              </div>
            )}

            <Button
              type="submit"
              variant="primary"
              disabled={isSubmitting || faultTooLong}
              aria-busy={isSubmitting}
              style={{ minHeight: '48px', fontSize: 'var(--token-fs-16)' }}
            >
              {isSubmitting ? 'Submitting…' : 'Submit request'}
            </Button>
          </div>
        </form>
      )}
    </main>
  )
}
