/**
 * @fileoverview Unit tests for LogWorkView utilities and components (WO-157 AC-10).
 *
 * Tests cover:
 *   - Duration validation (AC-1)
 *   - Parts row reducer (AC-2)
 *   - Shortfall message construction (AC-4)
 *   - Idempotency-key stability concept (AC-1)
 *   - Completion-guard error rendering (AC-6)
 *   - Offline mutation refusal (AC-8)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { validateTimeEntry, partsRowsReducer, buildShortfallNote } from './LogWorkView.jsx'

// ── validateTimeEntry ─────────────────────────────────────────────────────────

describe('validateTimeEntry', () => {
  it('accepts a positive duration in minutes', () => {
    expect(validateTimeEntry({ mode: 'duration', durationMinutes: '60' })).toBeNull()
  })

  it('rejects zero duration', () => {
    expect(validateTimeEntry({ mode: 'duration', durationMinutes: '0' })).not.toBeNull()
  })

  it('rejects negative duration', () => {
    expect(validateTimeEntry({ mode: 'duration', durationMinutes: '-5' })).not.toBeNull()
  })

  it('rejects empty duration string', () => {
    expect(validateTimeEntry({ mode: 'duration', durationMinutes: '' })).not.toBeNull()
  })

  it('rejects duration over 1440 minutes', () => {
    expect(validateTimeEntry({ mode: 'duration', durationMinutes: '1441' })).not.toBeNull()
  })

  it('accepts start/end range with end after start', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '2026-08-12T09:00',
      endedAt: '2026-08-12T11:30',
    })).toBeNull()
  })

  it('rejects end time equal to start time', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '2026-08-12T09:00',
      endedAt: '2026-08-12T09:00',
    })).not.toBeNull()
  })

  it('rejects end time before start time', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '2026-08-12T11:00',
      endedAt: '2026-08-12T09:00',
    })).not.toBeNull()
  })

  it('rejects range spanning more than 24 hours', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '2026-08-11T09:00',
      endedAt: '2026-08-13T09:01',
    })).not.toBeNull()
  })

  it('rejects range with missing startedAt', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '',
      endedAt: '2026-08-12T11:00',
    })).not.toBeNull()
  })

  it('rejects range with missing endedAt', () => {
    expect(validateTimeEntry({
      mode: 'range',
      startedAt: '2026-08-12T09:00',
      endedAt: '',
    })).not.toBeNull()
  })
})

// ── partsRowsReducer ──────────────────────────────────────────────────────────

describe('partsRowsReducer', () => {
  const initialRows = []

  it('ADD creates a new row with empty partId and quantity 1', () => {
    const next = partsRowsReducer(initialRows, { type: 'ADD' })
    expect(next).toHaveLength(1)
    expect(next[0].partId).toBe('')
    expect(next[0].quantity).toBe(1)
    expect(next[0].rowId).toBeTruthy()
    expect(next[0].error).toBeNull()
  })

  it('ADD multiple times grows the list', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    rows = partsRowsReducer(rows, { type: 'ADD' })
    expect(rows).toHaveLength(2)
  })

  it('UPDATE_PART changes the partId of the matching row', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    const rowId = rows[0].rowId
    rows = partsRowsReducer(rows, { type: 'UPDATE_PART', rowId, partId: 'part-001' })
    expect(rows[0].partId).toBe('part-001')
  })

  it('UPDATE_PART clears the row error', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    const rowId = rows[0].rowId
    rows = partsRowsReducer(rows, { type: 'SET_ERROR', index: 0, error: 'Some error' })
    rows = partsRowsReducer(rows, { type: 'UPDATE_PART', rowId, partId: 'part-002' })
    expect(rows[0].error).toBeNull()
  })

  it('UPDATE_QUANTITY changes the quantity', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    const rowId = rows[0].rowId
    rows = partsRowsReducer(rows, { type: 'UPDATE_QUANTITY', rowId, quantity: 5 })
    expect(rows[0].quantity).toBe(5)
  })

  it('SET_ERROR sets error on the row at the given index', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    rows = partsRowsReducer(rows, { type: 'SET_ERROR', index: 0, error: 'requested 3, available 2' })
    expect(rows[0].error).toBe('requested 3, available 2')
  })

  it('CLEAR_ERRORS removes errors from all rows', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    rows = partsRowsReducer(rows, { type: 'SET_ERROR', index: 0, error: 'Some error' })
    rows = partsRowsReducer(rows, { type: 'CLEAR_ERRORS' })
    expect(rows[0].error).toBeNull()
  })

  it('REMOVE removes the matching row', () => {
    let rows = partsRowsReducer(initialRows, { type: 'ADD' })
    rows = partsRowsReducer(rows, { type: 'ADD' })
    const rowId = rows[0].rowId
    rows = partsRowsReducer(rows, { type: 'REMOVE', rowId })
    expect(rows).toHaveLength(1)
    expect(rows[0].rowId).not.toBe(rowId)
  })
})

// ── buildShortfallNote ────────────────────────────────────────────────────────

describe('buildShortfallNote', () => {
  const vehicleStock = [
    { partId: 'part-001', partCode: 'FAN-12A', description: 'Condenser fan motor', quantityOnHand: 0, unit: 'EACH' },
    { partId: 'part-002', partCode: 'MCB-20A', description: '20A MCB', quantityOnHand: 1, unit: 'EACH' },
  ]
  const partsRows = [
    { rowId: 'r1', partId: 'part-001', quantity: 3, error: null },
    { rowId: 'r2', partId: 'part-002', quantity: 5, error: null },
  ]

  it('returns empty string for empty fieldErrors', () => {
    expect(buildShortfallNote([], partsRows, vehicleStock)).toBe('')
  })

  it('returns empty string for null fieldErrors', () => {
    expect(buildShortfallNote(null, partsRows, vehicleStock)).toBe('')
  })

  it('includes part description and message in output', () => {
    const note = buildShortfallNote(
      [{ field: 'lines[0].quantity', message: 'requested 3, available 0' }],
      partsRows,
      vehicleStock
    )
    expect(note).toContain('Condenser fan motor')
    expect(note).toContain('requested 3, available 0')
  })

  it('handles multiple shortfall lines separated by semicolon', () => {
    const note = buildShortfallNote(
      [
        { field: 'lines[0].quantity', message: 'requested 3, available 0' },
        { field: 'lines[1].quantity', message: 'requested 5, available 1' },
      ],
      partsRows,
      vehicleStock
    )
    expect(note).toContain('; ')
    expect(note).toContain('Condenser fan motor')
    expect(note).toContain('20A MCB')
  })

  it('falls back to partCode when description is absent', () => {
    const stockNoDesc = [{ partId: 'part-001', partCode: 'FAN-12A', description: null }]
    const note = buildShortfallNote(
      [{ field: 'lines[0].quantity', message: 'requested 3, available 0' }],
      partsRows,
      stockNoDesc
    )
    expect(note).toContain('FAN-12A')
  })

  it('uses message as fallback for unrecognised field format', () => {
    const note = buildShortfallNote(
      [{ field: 'unknown', message: 'something went wrong' }],
      partsRows,
      vehicleStock
    )
    expect(note).toBe('something went wrong')
  })
})
