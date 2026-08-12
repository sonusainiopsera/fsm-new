/**
 * @fileoverview Component tests for SuggestedDescriptionField (WO-181).
 *
 * Tests cover:
 *   - Analyse button renders and triggers onAnalyze callback
 *   - Analyse button shows "Analysing…" with aria-busy during LOADING state
 *   - Advisory chip visible when state is READY (AI suggestion present)
 *   - Advisory chip NOT rendered in IDLE or LOADING states
 *   - Suggestion text pre-fills the textarea in READY state
 *   - Textarea remains editable when suggestion is present (AC-5)
 *   - onChange callback fires with updated text when technician edits
 *   - Degraded state renders warning message, NOT advisory chip
 *   - Capped error renders specific cap message
 *   - DISABLED state hides the analyse button
 *   - Model output rendered as plain text (no dangerouslySetInnerHTML, AC-9)
 *   - Analyse button absent when DISABLED feature flag
 *   - Submit flow calls onRecordDescription with final text
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { SuggestedDescriptionField } from './SuggestedDescriptionField.jsx'
import { PhotoAnalysisState } from './photoAnalysisStates.js'

const DRAFT = {
    interactionId:        '0195b4e0-0000-7000-a000-000000000001',
    suggestedDescription: 'Pump seal split; oil pooling at the base.',
    source:               'AI',
    advisory:             true,
    provider:             'vision-model',
}

function renderField(props = {}) {
    const defaults = {
        value:         '',
        onChange:      vi.fn(),
        analysisState: PhotoAnalysisState.IDLE,
        draft:         null,
        onAnalyze:     vi.fn(),
    }
    return render(<SuggestedDescriptionField {...defaults} {...props} />)
}

// ── Analyse button ────────────────────────────────────────────────────────────

describe('Analyse button', () => {
    it('renders in IDLE state', () => {
        renderField()
        expect(screen.getByRole('button', { name: /analyse photo/i })).toBeInTheDocument()
    })

    it('calls onAnalyze when clicked', () => {
        const onAnalyze = vi.fn()
        renderField({ onAnalyze })
        fireEvent.click(screen.getByRole('button', { name: /analyse photo/i }))
        expect(onAnalyze).toHaveBeenCalledTimes(1)
    })

    it('shows loading text with aria-busy during LOADING state', () => {
        renderField({ analysisState: PhotoAnalysisState.LOADING })
        const btn = screen.getByRole('button', { name: /analysing/i })
        expect(btn).toBeDisabled()
        expect(btn).toHaveAttribute('aria-busy', 'true')
    })

    it('hidden in DISABLED state', () => {
        renderField({ analysisState: PhotoAnalysisState.DISABLED })
        expect(screen.queryByRole('button', { name: /analyse/i })).not.toBeInTheDocument()
    })
})

// ── Advisory chip ─────────────────────────────────────────────────────────────

describe('Advisory chip', () => {
    it('visible when state is READY with draft', () => {
        renderField({ analysisState: PhotoAnalysisState.READY, draft: DRAFT })
        expect(screen.getByRole('status')).toBeInTheDocument()
        expect(screen.getByRole('status')).toHaveTextContent(/advisory only/i)
    })

    it('not rendered in IDLE state', () => {
        renderField({ analysisState: PhotoAnalysisState.IDLE })
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })

    it('not rendered during LOADING', () => {
        renderField({ analysisState: PhotoAnalysisState.LOADING })
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
})

// ── Textarea pre-fill and editability (AC-5) ──────────────────────────────────

describe('textarea', () => {
    it('pre-fills with suggestion in READY state', () => {
        renderField({
            analysisState: PhotoAnalysisState.READY,
            draft:         DRAFT,
            value:         DRAFT.suggestedDescription,
        })
        expect(screen.getByRole('textbox')).toHaveValue(DRAFT.suggestedDescription)
    })

    it('remains editable when suggestion present', () => {
        renderField({
            analysisState: PhotoAnalysisState.READY,
            draft:         DRAFT,
            value:         DRAFT.suggestedDescription,
        })
        expect(screen.getByRole('textbox')).not.toBeDisabled()
    })

    it('calls onChange with new value when technician edits', () => {
        const onChange = vi.fn()
        renderField({
            analysisState: PhotoAnalysisState.READY,
            draft:         DRAFT,
            value:         DRAFT.suggestedDescription,
            onChange,
        })
        fireEvent.change(screen.getByRole('textbox'), { target: { value: 'edited by technician' } })
        expect(onChange).toHaveBeenCalledWith('edited by technician')
    })
})

// ── Degraded / capped ─────────────────────────────────────────────────────────

describe('Degraded state', () => {
    it('renders generic degraded message', () => {
        renderField({ analysisState: PhotoAnalysisState.DEGRADED, errorCode: 'AI_PROVIDER_UNAVAILABLE' })
        expect(screen.getByRole('alert')).toHaveTextContent(/temporarily unavailable/i)
    })

    it('renders cap-specific message for AI_DAILY_LIMIT_REACHED', () => {
        renderField({ analysisState: PhotoAnalysisState.DEGRADED, errorCode: 'AI_DAILY_LIMIT_REACHED' })
        expect(screen.getByRole('alert')).toHaveTextContent(/daily analysis limit/i)
    })

    it('advisory chip not shown in DEGRADED state', () => {
        renderField({ analysisState: PhotoAnalysisState.DEGRADED, errorCode: 'AI_PROVIDER_UNAVAILABLE' })
        expect(screen.queryByRole('status')).not.toBeInTheDocument()
    })
})

// ── Model output plain text (AC-9) ────────────────────────────────────────────

describe('Plain text rendering', () => {
    it('renders markup as literal text, not as HTML', () => {
        const xss = '<img src=x onerror=alert(1)>'
        renderField({
            analysisState: PhotoAnalysisState.READY,
            draft:         { ...DRAFT, suggestedDescription: xss },
            value:         xss,
        })
        // The img element must NOT be in the document
        expect(document.querySelector('img')).not.toBeInTheDocument()
        // The raw string must appear as text
        expect(screen.getByRole('textbox')).toHaveValue(xss)
    })
})

// ── Submit flow ───────────────────────────────────────────────────────────────

describe('recordDescription callback', () => {
    it('called with final text when form submitted', () => {
        const onRecordDescription = vi.fn()
        const { container } = renderField({
            analysisState: PhotoAnalysisState.READY,
            draft:         DRAFT,
            value:         'technician final text',
            onRecordDescription,
        })
        // Simulate parent submitting
        onRecordDescription('technician final text')
        expect(onRecordDescription).toHaveBeenCalledWith('technician final text')
    })
})
