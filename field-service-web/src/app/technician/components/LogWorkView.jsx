/**
 * @fileoverview LogWorkView — technician log-work screen for labour time and parts (WO-157).
 *
 * Composed of:
 *   TimeEntryCard   — duration or start/end mode, work-performed note
 *   PartsRowsList   — repeatable rows bound to vehicle stock
 *   PhotoStrip      — upload thumbnails with pending/error state
 *   CompleteActionBar — sticky bar that posts labour, parts, and the COMPLETE transition
 *
 * Security constraints:
 * - All mutations refused when offline (AC-8).
 * - Idempotency key minted per complete-intent, reused on retry (AC-1).
 * - 422 INSUFFICIENT_STOCK names the short part and offers awaiting-parts hold path (AC-4).
 * - 422 GUARD_FAILED on complete rendered inline naming the missing evidence (AC-6).
 */
import { useState, useRef, useCallback } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { get, post } from '../../../api/http.js'
import { mapApiError } from '../../../shared/api/errorMapping.js'
import { useConnectivity } from '../../../shared/hooks/useConnectivity.js'
import { HoldReasonSheet } from './HoldReasonSheet.jsx'
import { LoadingState, ErrorState } from '../../../components/index.js'
import styles from './LogWorkView.module.css'

// ── Constants ─────────────────────────────────────────────────────────────────

const NOTE_MAX = 500
const HOLD_REASONS_FOR_SHORTFALL = [
  { code: 'AWAITING_PARTS', label: 'Awaiting parts', sortOrder: 1, pausesSLAClock: true },
]

// ── Query keys ────────────────────────────────────────────────────────────────

const stockQueryKey = ['technician', 'vehicle-stock']
const detailQueryKey = (id) => ['technician', 'job-detail', id]
const dayListQueryKey = ['technician', 'day-list']

// ── Idempotency ───────────────────────────────────────────────────────────────

function mintKey() {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Array.from({ length: 32 }, () => Math.floor(Math.random() * 16).toString(16)).join('')
}

// ── Duration validation ───────────────────────────────────────────────────────

export function validateTimeEntry(entry) {
  if (entry.mode === 'duration') {
    const mins = parseInt(entry.durationMinutes, 10)
    if (!mins || mins <= 0) return 'Duration must be a positive number of minutes.'
    if (mins > 1440) return 'Duration cannot exceed 1440 minutes (24 hours).'
    return null
  }
  if (!entry.startedAt) return 'Start time is required.'
  if (!entry.endedAt) return 'End time is required.'
  const start = new Date(entry.startedAt).getTime()
  const end = new Date(entry.endedAt).getTime()
  if (isNaN(start) || isNaN(end)) return 'Invalid date/time value.'
  if (end <= start) return 'End time must be after start time.'
  const diffMins = (end - start) / 60_000
  if (diffMins > 1440) return 'Labour period cannot span more than 24 hours.'
  return null
}

// ── Shortfall helpers ─────────────────────────────────────────────────────────

export function buildShortfallNote(fieldErrors, partsRows, vehicleStock) {
  if (!fieldErrors?.length) return ''
  return fieldErrors
    .map((fe) => {
      const match = fe.field?.match(/lines\[(\d+)\]/)
      if (!match) return fe.message
      const idx = parseInt(match[1], 10)
      const row = partsRows[idx]
      const stockItem = vehicleStock?.find((s) => s.partId === row?.partId)
      const name = stockItem?.description ?? stockItem?.partCode ?? `Part #${idx + 1}`
      return `${name}: ${fe.message}`
    })
    .join('; ')
}

// ── Parts row reducer ─────────────────────────────────────────────────────────

export function partsRowsReducer(rows, action) {
  switch (action.type) {
    case 'ADD':
      return [...rows, { rowId: mintKey(), partId: '', quantity: 1, error: null }]
    case 'UPDATE_PART':
      return rows.map((r) => r.rowId === action.rowId ? { ...r, partId: action.partId, error: null } : r)
    case 'UPDATE_QUANTITY':
      return rows.map((r) => r.rowId === action.rowId ? { ...r, quantity: action.quantity, error: null } : r)
    case 'SET_ERROR':
      return rows.map((r, i) => i === action.index ? { ...r, error: action.error } : r)
    case 'CLEAR_ERRORS':
      return rows.map((r) => ({ ...r, error: null }))
    case 'REMOVE':
      return rows.filter((r) => r.rowId !== action.rowId)
    default:
      return rows
  }
}

// ── TimeEntryCard ─────────────────────────────────────────────────────────────

function TimeEntryCard({ entry, onChange, error }) {
  function set(field, value) { onChange({ ...entry, [field]: value }) }

  return (
    <section className={styles.card} aria-label="Labour time entry">
      <h2 className={styles.sectionTitle}>Time on job</h2>

      <div className={styles.modeTabs} role="group" aria-label="Time entry mode">
        <button
          type="button"
          className={entry.mode === 'duration' ? styles.modeTabActive : styles.modeTab}
          onClick={() => set('mode', 'duration')}
          aria-pressed={entry.mode === 'duration'}
          data-testid="mode-duration"
        >
          Duration
        </button>
        <button
          type="button"
          className={entry.mode === 'range' ? styles.modeTabActive : styles.modeTab}
          onClick={() => set('mode', 'range')}
          aria-pressed={entry.mode === 'range'}
          data-testid="mode-range"
        >
          Start / End
        </button>
      </div>

      {entry.mode === 'duration' ? (
        <div className={styles.field}>
          <label htmlFor="duration-mins" className={styles.label}>
            Duration (minutes) <span aria-hidden="true">*</span>
          </label>
          <input
            id="duration-mins"
            type="number"
            className={styles.input}
            min={1}
            max={1440}
            value={entry.durationMinutes}
            onChange={(e) => set('durationMinutes', e.target.value)}
            data-testid="duration-input"
            aria-required="true"
          />
        </div>
      ) : (
        <>
          <div className={styles.field}>
            <label htmlFor="started-at" className={styles.label}>
              Start time <span aria-hidden="true">*</span>
            </label>
            <input
              id="started-at"
              type="datetime-local"
              className={styles.input}
              value={entry.startedAt}
              onChange={(e) => set('startedAt', e.target.value)}
              data-testid="started-at-input"
            />
          </div>
          <div className={styles.field}>
            <label htmlFor="ended-at" className={styles.label}>
              End time <span aria-hidden="true">*</span>
            </label>
            <input
              id="ended-at"
              type="datetime-local"
              className={styles.input}
              value={entry.endedAt}
              onChange={(e) => set('endedAt', e.target.value)}
              data-testid="ended-at-input"
            />
          </div>
        </>
      )}

      <div className={styles.field}>
        <label htmlFor="work-note" className={styles.label}>
          Work performed (optional)
        </label>
        <textarea
          id="work-note"
          className={styles.textarea}
          value={entry.note}
          onChange={(e) => set('note', e.target.value.slice(0, NOTE_MAX))}
          rows={3}
          maxLength={NOTE_MAX}
          data-testid="work-note-input"
          aria-describedby="work-note-counter"
        />
        <span id="work-note-counter" className={styles.counter}>{entry.note.length}/{NOTE_MAX}</span>
      </div>

      {error && (
        <p className={styles.fieldError} role="alert" data-testid="time-entry-error">
          {error}
        </p>
      )}
    </section>
  )
}

// ── PartsRowsList ─────────────────────────────────────────────────────────────

function PartsRowsList({ rows, dispatch, vehicleStock, stockLoading }) {
  return (
    <section className={styles.card} aria-label="Parts consumed">
      <h2 className={styles.sectionTitle}>Parts used</h2>

      {stockLoading && <p className={styles.stockLoading}>Loading vehicle stock…</p>}

      {rows.length === 0 && !stockLoading && (
        <p className={styles.emptyParts}>No parts logged yet.</p>
      )}

      <ul className={styles.partRowList} aria-label="Parts rows">
        {rows.map((row, idx) => (
          <li key={row.rowId} className={styles.partRow}>
            <div className={styles.partRowFields}>
              <div className={styles.field}>
                <label htmlFor={`part-select-${row.rowId}`} className={styles.label}>
                  Part
                </label>
                <select
                  id={`part-select-${row.rowId}`}
                  className={styles.select}
                  value={row.partId}
                  onChange={(e) => dispatch({ type: 'UPDATE_PART', rowId: row.rowId, partId: e.target.value })}
                  data-testid={`part-select-${idx}`}
                  aria-label={`Part for row ${idx + 1}`}
                >
                  <option value="">Select a part…</option>
                  {(vehicleStock ?? []).map((item) => (
                    <option key={item.partId} value={item.partId} disabled={item.quantityOnHand === 0}>
                      {item.partCode}{item.description ? ` — ${item.description}` : ''} ({item.quantityOnHand} {item.unit})
                    </option>
                  ))}
                </select>
              </div>

              <div className={styles.field}>
                <label htmlFor={`qty-input-${row.rowId}`} className={styles.label}>
                  Quantity
                </label>
                <input
                  id={`qty-input-${row.rowId}`}
                  type="number"
                  className={styles.qtyInput}
                  min={1}
                  value={row.quantity}
                  onChange={(e) => dispatch({ type: 'UPDATE_QUANTITY', rowId: row.rowId, quantity: parseInt(e.target.value, 10) || 1 })}
                  data-testid={`qty-input-${idx}`}
                  aria-label={`Quantity for row ${idx + 1}`}
                />
              </div>
            </div>

            {row.error && (
              <p className={styles.fieldError} role="alert" data-testid={`part-row-error-${idx}`}>
                {row.error}
              </p>
            )}

            <button
              type="button"
              className={styles.removeRowButton}
              onClick={() => dispatch({ type: 'REMOVE', rowId: row.rowId })}
              aria-label={`Remove part row ${idx + 1}`}
              data-testid={`remove-row-${idx}`}
            >
              Remove
            </button>
          </li>
        ))}
      </ul>

      <button
        type="button"
        className={styles.addRowButton}
        onClick={() => dispatch({ type: 'ADD' })}
        data-testid="add-part-row"
      >
        + Add part
      </button>
    </section>
  )
}

// ── PhotoStrip ────────────────────────────────────────────────────────────────

function PhotoStrip({ photos, onAdd, onRemove, onRetry }) {
  function handleFileInput(e) {
    const files = Array.from(e.target.files ?? [])
    files.forEach((file) => {
      const url = URL.createObjectURL(file)
      onAdd({ id: mintKey(), url, name: file.name, status: 'pending' })
    })
    e.target.value = ''
  }

  return (
    <section className={styles.card} aria-label="Evidence photos">
      <h2 className={styles.sectionTitle}>Photos</h2>

      {photos.length === 0 && (
        <p className={styles.emptyPhotos}>No photos attached yet.</p>
      )}

      {photos.length > 0 && (
        <ul className={styles.photoList} aria-label="Attached photos">
          {photos.map((photo) => (
            <li key={photo.id} className={styles.photoItem} data-testid={`photo-${photo.id}`}>
              <img
                src={photo.url}
                alt={photo.name}
                className={styles.photoThumb}
                width={72}
                height={72}
              />
              <div className={styles.photoMeta}>
                <span className={styles.photoName}>{photo.name}</span>
                <span
                  className={photo.status === 'error' ? styles.photoStatusError : styles.photoStatus}
                  data-status={photo.status}
                >
                  {photo.status === 'pending' ? 'Uploading…' : photo.status === 'error' ? 'Upload failed' : 'Uploaded'}
                </span>
              </div>
              {photo.status === 'error' && (
                <button
                  type="button"
                  className={styles.retryPhoto}
                  onClick={() => onRetry(photo.id)}
                  aria-label={`Retry upload for ${photo.name}`}
                  data-testid={`retry-photo-${photo.id}`}
                >
                  Retry
                </button>
              )}
              <button
                type="button"
                className={styles.removePhoto}
                onClick={() => onRemove(photo.id)}
                aria-label={`Remove photo ${photo.name}`}
                data-testid={`remove-photo-${photo.id}`}
              >
                ✕
              </button>
            </li>
          ))}
        </ul>
      )}

      <label htmlFor="photo-input" className={styles.addPhotoLabel}>
        + Add photo
        <input
          id="photo-input"
          type="file"
          accept="image/*"
          multiple
          onChange={handleFileInput}
          className={styles.photoFileInput}
          data-testid="photo-input"
        />
      </label>
    </section>
  )
}

// ── CompleteActionBar ─────────────────────────────────────────────────────────

function CompleteActionBar({ onComplete, isSubmitting, guardMessage, offlineMessage }) {
  return (
    <div className={styles.completeBar} data-testid="complete-action-bar">
      {offlineMessage && (
        <p className={styles.offlineMessage} role="alert" data-testid="offline-message">
          {offlineMessage}
        </p>
      )}
      {guardMessage && (
        <p className={styles.guardMessage} role="alert" data-testid="complete-guard-message">
          {guardMessage}
        </p>
      )}
      <button
        type="button"
        className={styles.completeButton}
        onClick={onComplete}
        disabled={isSubmitting || !!offlineMessage}
        aria-busy={isSubmitting}
        data-testid="complete-button"
      >
        {isSubmitting ? 'Saving…' : 'Save & Complete'}
      </button>
    </div>
  )
}

// ── Main view ─────────────────────────────────────────────────────────────────

const INITIAL_TIME_ENTRY = {
  mode: 'duration',
  durationMinutes: '',
  startedAt: '',
  endedAt: '',
  note: '',
}

/**
 * @param {{ workOrderId: string, holdReasons?: Array }} props
 */
export function LogWorkView({ workOrderId, holdReasons = [] }) {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const { guardMutation, isConnected } = useConnectivity()

  // ── Form state ──────────────────────────────────────────────────────────────

  const [timeEntry, setTimeEntry] = useState(INITIAL_TIME_ENTRY)
  const [timeError, setTimeError] = useState(null)
  const [partsRows, setPartsRows] = useState([])
  const [photos, setPhotos] = useState([])
  const [guardMessage, setGuardMessage] = useState(null)
  const [shortfallInfo, setShortfallInfo] = useState(null) // { fieldErrors, note }
  const [showHoldSheet, setShowHoldSheet] = useState(false)
  const [holdPending, setHoldPending] = useState(false)
  const [holdError, setHoldError] = useState(null)

  // ── Idempotency keys (per complete-intent, reused on retry) ─────────────────

  const labourKeyRef = useRef(null)
  const partsKeyRef = useRef(null)

  // ── Vehicle stock ───────────────────────────────────────────────────────────

  const { data: stockData, isLoading: stockLoading } = useQuery({
    queryKey: stockQueryKey,
    queryFn: () => get('/technicians/me/stock'),
    staleTime: 60_000,
  })
  const vehicleStock = stockData?.data ?? []

  // ── Parts rows dispatch ─────────────────────────────────────────────────────

  function partsDispatch(action) {
    setPartsRows((prev) => partsRowsReducer(prev, action))
  }

  // ── Submit mutations ────────────────────────────────────────────────────────

  const labourMutation = useMutation({
    mutationFn: ({ key, body }) =>
      post(`/work-orders/${workOrderId}/labour`, body, { headers: { 'Idempotency-Key': key } }),
    retry: false,
  })

  const partsMutation = useMutation({
    mutationFn: ({ key, lines }) =>
      post(`/work-orders/${workOrderId}/parts-consumption`, { lines }, { headers: { 'Idempotency-Key': key } }),
    retry: false,
  })

  const completeMutation = useMutation({
    mutationFn: () =>
      post(`/work-orders/${workOrderId}/transitions`, { event: 'COMPLETE' }),
    retry: false,
  })

  const isSubmitting = labourMutation.isPending || partsMutation.isPending || completeMutation.isPending

  // ── Handle complete ─────────────────────────────────────────────────────────

  const handleComplete = useCallback(async () => {
    if (!guardMutation()) return // offline

    const hasPendingUploads = photos.some((p) => p.status === 'pending')
    if (hasPendingUploads) {
      setGuardMessage('Some photos are still uploading. Wait for uploads to finish before completing.')
      return
    }

    // Client-side time entry validation
    const timeValidError = validateTimeEntry(timeEntry)
    if (timeValidError) {
      setTimeError(timeValidError)
      return
    }
    setTimeError(null)
    setGuardMessage(null)

    // Mint idempotency keys once per intent (reused on network-error retry)
    if (!labourKeyRef.current) labourKeyRef.current = mintKey()
    if (!partsKeyRef.current) partsKeyRef.current = mintKey()

    // ── Step 1: POST labour ──────────────────────────────────────────────────

    try {
      let labourBody
      if (timeEntry.mode === 'duration') {
        labourBody = {
          durationMinutes: parseInt(timeEntry.durationMinutes, 10),
          note: timeEntry.note || null,
        }
      } else {
        labourBody = {
          startedAt: new Date(timeEntry.startedAt).toISOString(),
          endedAt: new Date(timeEntry.endedAt).toISOString(),
          note: timeEntry.note || null,
        }
      }
      await labourMutation.mutateAsync({ key: labourKeyRef.current, body: labourBody })
    } catch (err) {
      const mapped = mapApiError(err)
      setGuardMessage(mapped.message)
      return
    }

    // ── Step 2: POST parts consumption ───────────────────────────────────────

    // Track original indices so server line[N] maps back to the correct partsRows slot
    const filledRows = partsRows
      .map((r, originalIndex) => ({ ...r, originalIndex }))
      .filter((r) => r.partId)

    if (filledRows.length > 0) {
      try {
        const lines = filledRows.map((r) => ({ partId: r.partId, quantity: r.quantity }))
        await partsMutation.mutateAsync({ key: partsKeyRef.current, lines })
        partsDispatch({ type: 'CLEAR_ERRORS' })
      } catch (err) {
        const mapped = mapApiError(err)
        if (err?.code === 'INSUFFICIENT_STOCK') {
          const fieldErrors = err?.fieldErrors ?? []
          fieldErrors.forEach((fe) => {
            const match = fe.field?.match(/lines\[(\d+)\]/)
            if (match) {
              const filledIdx = parseInt(match[1], 10)
              const originalIdx = filledRows[filledIdx]?.originalIndex ?? filledIdx
              partsDispatch({ type: 'SET_ERROR', index: originalIdx, error: fe.message })
            }
          })
          const shortfallNote = buildShortfallNote(fieldErrors, filledRows, vehicleStock)
          setShortfallInfo({ fieldErrors, note: shortfallNote })
          return
        }
        setGuardMessage(mapped.message)
        return
      }
    }

    // ── Step 3: POST COMPLETE transition ─────────────────────────────────────

    try {
      await completeMutation.mutateAsync()
    } catch (err) {
      const mapped = mapApiError(err)
      setGuardMessage(mapped.message)
      return
    }

    // ── Success: invalidate caches and navigate ───────────────────────────────

    labourKeyRef.current = null
    partsKeyRef.current = null
    queryClient.invalidateQueries({ queryKey: detailQueryKey(workOrderId) })
    queryClient.invalidateQueries({ queryKey: dayListQueryKey })
    navigate(-1)
  }, [
    guardMutation, photos, timeEntry, partsRows, vehicleStock, workOrderId,
    labourMutation, partsMutation, completeMutation, queryClient, navigate,
  ])

  // ── Hold sheet (for shortfall) ──────────────────────────────────────────────

  const handleHoldSubmit = useCallback(async (reasonCode, note) => {
    setHoldPending(true)
    setHoldError(null)
    const key = mintKey()
    try {
      await post(
        `/work-orders/${workOrderId}/transitions`,
        { event: 'HOLD', reasonCode, note },
        { headers: { 'Idempotency-Key': key } }
      )
      setShowHoldSheet(false)
      setShortfallInfo(null)
      queryClient.invalidateQueries({ queryKey: detailQueryKey(workOrderId) })
      queryClient.invalidateQueries({ queryKey: dayListQueryKey })
      navigate(-1)
    } catch (err) {
      setHoldError(err?.message ?? 'Hold failed. Please try again.')
    } finally {
      setHoldPending(false)
    }
  }, [workOrderId, queryClient, navigate])

  const offlineMessage = !isConnected
    ? 'Not connected. All saves are blocked until connectivity is restored.'
    : null

  return (
    <div className={styles.view} data-testid="log-work-view">
      <TimeEntryCard
        entry={timeEntry}
        onChange={setTimeEntry}
        error={timeError}
      />

      <PartsRowsList
        rows={partsRows}
        dispatch={partsDispatch}
        vehicleStock={vehicleStock}
        stockLoading={stockLoading}
      />

      <PhotoStrip
        photos={photos}
        onAdd={(photo) => setPhotos((prev) => [...prev, photo])}
        onRemove={(id) => setPhotos((prev) => prev.filter((p) => p.id !== id))}
        onRetry={(id) => setPhotos((prev) =>
          prev.map((p) => p.id === id ? { ...p, status: 'pending' } : p)
        )}
      />

      {/* Shortfall shortcut to hold sheet */}
      {shortfallInfo && !showHoldSheet && (
        <div className={styles.shortfallBanner} role="alert" data-testid="shortfall-banner">
          <p className={styles.shortfallMessage}>
            Insufficient stock — parts not available on your vehicle.
          </p>
          <button
            type="button"
            className={styles.shortfallHoldButton}
            onClick={() => setShowHoldSheet(true)}
            data-testid="shortfall-hold-button"
          >
            Place on hold — awaiting parts
          </button>
        </div>
      )}

      <div className={styles.completeBarContainer}>
        <CompleteActionBar
          onComplete={handleComplete}
          isSubmitting={isSubmitting}
          guardMessage={guardMessage}
          offlineMessage={offlineMessage}
        />
      </div>

      {showHoldSheet && (
        <div className={styles.sheetOverlay} role="presentation">
          <HoldReasonSheet
            holdReasons={holdReasons.length > 0 ? holdReasons : HOLD_REASONS_FOR_SHORTFALL}
            initialCode="AWAITING_PARTS"
            initialNote={shortfallInfo?.note ?? ''}
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
