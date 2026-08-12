/**
 * @fileoverview JobDetailView — full job detail for the technician mobile surface (WO-156).
 *
 * Composed of:
 *   ContextCard   — site, contact, asset, fault, certifications, expected parts
 *   SlaCountdownChip — live SLA countdown from shared/components/DeadlineCountdown
 *   AssetHistoryList — last 5 closed work orders for the asset (from API)
 *   TransitionActionBar — server-driven action bar
 *   HoldReasonSheet — controlled hold reason overlay
 *
 * Mutation flow:
 *   1. Tap action → TransitionActionBar posts to transitions endpoint with Idempotency-Key
 *   2. 200 → invalidate work order + day-list cache, show success toast
 *   3. 409 → refetch the work order (conflict-refresh), show non-destructive notice
 *   4. 422 → show guard message verbatim at the action bar (state unchanged)
 */
import { useState, useCallback, useEffect } from 'react'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { get, post } from '../../../api/http.js'
import { DeadlineCountdown } from '../../../shared/components/DeadlineCountdown.jsx'
import { TransitionActionBar } from './TransitionActionBar.jsx'
import { HoldReasonSheet } from './HoldReasonSheet.jsx'
import { EmptyState, LoadingState, ErrorState } from '../../../components/index.js'
import { usePositionReporting } from '../hooks/usePositionReporting.js'
import { usePositionSharing } from '../PositionSharingContext.jsx'
import styles from './JobDetailView.module.css'

// ── Query keys ────────────────────────────────────────────────────────────────

const detailQueryKey = (id) => ['technician', 'job-detail', id]
const assetHistoryQueryKey = (assetId) => ['asset', 'service-history', assetId]
const dayListQueryKey = ['technician', 'day-list']

// ── Fetchers ──────────────────────────────────────────────────────────────────

function fetchJobDetail(id, { signal }) {
  return get(`/technicians/me/work-orders/${id}`, { signal })
}

function fetchAssetHistory(assetId, { signal }) {
  return get(`/assets/${assetId}/service-history`, { signal })
}

async function postTransition(workOrderId, event, idempotencyKey) {
  return post(`/work-orders/${workOrderId}/transitions`,
    { event },
    { headers: { 'Idempotency-Key': idempotencyKey } }
  )
}

// ── Sub-components ────────────────────────────────────────────────────────────

function ContextCard({ job }) {
  return (
    <section className={styles.card} aria-label="Job context">
      <h1 className={styles.reference}>{job.reference}</h1>

      <div className={styles.badges}>
        <span className={styles.stateBadge} data-state={job.state}>{job.state}</span>
        <span className={styles.priorityBadge} data-priority={job.priority}>{job.priority}</span>
      </div>

      {job.faultDescription && (
        <p className={styles.faultDesc}>{job.faultDescription}</p>
      )}

      <dl className={styles.details}>
        {job.siteName && (
          <>
            <dt>Site</dt>
            <dd>{job.siteName}</dd>
          </>
        )}
        {job.siteAddress && (
          <>
            <dt>Address</dt>
            <dd>{job.siteAddress}{job.sitePostcode ? `, ${job.sitePostcode}` : ''}</dd>
          </>
        )}
        {job.siteAccessNotes && (
          <>
            <dt>Access</dt>
            <dd className={styles.accessNotes}>{job.siteAccessNotes}</dd>
          </>
        )}
        {job.contactName && (
          <>
            <dt>Contact</dt>
            <dd>{job.contactName}{job.contactPhoneMasked ? ` · ${job.contactPhoneMasked}` : ''}</dd>
          </>
        )}
        {job.assetTag && (
          <>
            <dt>Asset</dt>
            <dd>
              {job.assetTag}
              {job.assetDescription ? ` — ${job.assetDescription}` : ''}
              {job.assetModel ? ` (${job.assetModel})` : ''}
            </dd>
          </>
        )}
      </dl>

      {job.requiredCertifications?.length > 0 && (
        <div className={styles.tags}>
          <span className={styles.tagsLabel}>Required certs</span>
          {job.requiredCertifications.map(code => (
            <span key={code} className={styles.certChip}>{code}</span>
          ))}
        </div>
      )}

      {job.expectedParts?.length > 0 && (
        <div className={styles.partsList}>
          <span className={styles.tagsLabel}>Expected parts</span>
          <ul aria-label="Expected parts">
            {job.expectedParts.map(p => (
              <li key={p.partId} className={styles.partItem}>
                {p.partNumber ?? p.partId}
                {p.partName ? ` — ${p.partName}` : ''}
                {` ×${p.requiredQuantity}`}
              </li>
            ))}
          </ul>
        </div>
      )}
    </section>
  )
}

function AssetHistoryList({ assetId }) {
  const { data, isLoading } = useQuery({
    queryKey: assetHistoryQueryKey(assetId),
    queryFn: ({ signal }) => fetchAssetHistory(assetId, { signal }),
    enabled: !!assetId,
    staleTime: 5 * 60_000,
  })

  if (!assetId) return null
  if (isLoading) return <p className={styles.historyLoading}>Loading service history…</p>

  const history = Array.isArray(data) ? data : []

  if (history.length === 0) {
    return (
      <section className={styles.card} aria-label="Prior service history">
        <h2 className={styles.sectionTitle}>Prior service history</h2>
        <p className={styles.emptyHistory}>No prior service records for this asset.</p>
      </section>
    )
  }

  return (
    <section className={styles.card} aria-label="Prior service history">
      <h2 className={styles.sectionTitle}>Prior service history</h2>
      <ol className={styles.historyList} aria-label="Closed work orders for this asset">
        {history.map(item => (
          <li key={item.workOrderId} className={styles.historyItem}>
            <span className={styles.historyRef}>{item.reference}</span>
            {item.resolvedAt && (
              <span className={styles.historyDate}>
                {new Date(item.resolvedAt).toLocaleDateString()}
              </span>
            )}
            {item.faultSummary && (
              <p className={styles.historySummary}>{item.faultSummary}</p>
            )}
            {item.resolutionSummary && (
              <p className={styles.historyResolution}>{item.resolutionSummary}</p>
            )}
          </li>
        ))}
      </ol>
    </section>
  )
}

// ── Main view ─────────────────────────────────────────────────────────────────

/**
 * @param {{ workOrderId: string }} props
 */
export function JobDetailView({ workOrderId }) {
  const queryClient = useQueryClient()
  const [showHoldSheet, setShowHoldSheet] = useState(false)
  const [holdPending, setHoldPending] = useState(false)
  const [holdError, setHoldError] = useState(null)
  const [conflictNotice, setConflictNotice] = useState(null)

  const { data: job, isLoading, isError, error, refetch } = useQuery({
    queryKey: detailQueryKey(workOrderId),
    queryFn: ({ signal }) => fetchJobDetail(workOrderId, { signal }),
    staleTime: 30_000,
    retry: false,
  })

  const { isReporting } = usePositionReporting(job?.state)
  const { setIsSharing } = usePositionSharing()

  useEffect(() => {
    setIsSharing(isReporting)
    return () => setIsSharing(false)
  }, [isReporting, setIsSharing])

  const handleTransitionSuccess = useCallback((result) => {
    queryClient.invalidateQueries({ queryKey: detailQueryKey(workOrderId) })
    queryClient.invalidateQueries({ queryKey: dayListQueryKey })
  }, [queryClient, workOrderId])

  const handleConflict = useCallback((mapped) => {
    setConflictNotice(mapped.message)
    refetch()
  }, [refetch])

  const handleHoldSubmit = useCallback(async (reasonCode, note) => {
    if (!job) return
    setHoldPending(true)
    setHoldError(null)
    const key = (typeof crypto !== 'undefined' && crypto.randomUUID)
      ? crypto.randomUUID()
      : Date.now().toString()
    try {
      await post(`/work-orders/${workOrderId}/transitions`,
        { event: 'HOLD', reasonCode, note },
        { headers: { 'Idempotency-Key': key } }
      )
      setShowHoldSheet(false)
      handleTransitionSuccess(null)
    } catch (err) {
      setHoldError(err?.message ?? 'Hold failed. Please try again.')
    } finally {
      setHoldPending(false)
    }
  }, [job, workOrderId, handleTransitionSuccess])

  if (isLoading) return <LoadingState />
  if (isError) {
    if (error?.status === 403) {
      return <ErrorState message="You do not have permission to view this job." />
    }
    return <ErrorState message={error?.message ?? 'Unable to load job detail.'} />
  }
  if (!job) return <EmptyState message="Job not found." />

  return (
    <div className={styles.view} data-testid="job-detail-view">
      {/* Conflict notice (non-destructive, dismissible) */}
      {conflictNotice && (
        <div className={styles.conflictBanner} role="status" data-testid="conflict-notice">
          <span>{conflictNotice}</span>
          <button
            type="button"
            className={styles.dismissButton}
            onClick={() => setConflictNotice(null)}
            aria-label="Dismiss notice"
          >✕</button>
        </div>
      )}

      {/* SLA countdown */}
      {job.resolutionDeadline && (
        <div className={styles.slaRow}>
          <DeadlineCountdown
            deadlineAt={job.resolutionDeadline}
            atRiskAt={job.atRiskAt}
            label="Resolution"
          />
        </div>
      )}

      {/* Main context card */}
      <ContextCard job={job} />

      {/* Asset service history */}
      <AssetHistoryList assetId={job.assetId} />

      {/* Server-driven action bar */}
      <div className={styles.actionBarContainer}>
        <TransitionActionBar
          workOrderId={workOrderId}
          allowedTransitions={job.allowedTransitions ?? []}
          onTransitionSuccess={handleTransitionSuccess}
          onConflict={handleConflict}
          onGuardRefusal={(mapped) => { /* error shown in bar itself */ }}
          onHoldRequest={() => setShowHoldSheet(true)}
          postTransition={postTransition}
        />
      </div>

      {/* Hold reason sheet */}
      {showHoldSheet && (
        <div className={styles.sheetOverlay} role="presentation">
          <HoldReasonSheet
            holdReasons={job.holdReasons ?? []}
            onSubmit={handleHoldSubmit}
            onCancel={() => { setShowHoldSheet(false); setHoldError(null) }}
            isPending={holdPending}
            error={holdError}
          />
        </div>
      )}
    </div>
  )
}
