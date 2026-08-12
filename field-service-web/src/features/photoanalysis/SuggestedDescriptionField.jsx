/**
 * @fileoverview SuggestedDescriptionField — editable description field with AI attribution chip (WO-181).
 *
 * Security invariants:
 *   - AI suggestion pre-fills an editable <textarea>; it is NEVER auto-submitted.
 *   - Model output is always rendered as plain text — no dangerouslySetInnerHTML.
 *   - The Advisory chip is always visible when a suggestion is present (AC-5, AC-9).
 *   - Technician's text is authoritative; they can clear or fully replace the suggestion.
 *
 * Accessibility:
 *   - The Advisory chip is role="status" so screen readers announce it on appearance.
 *   - The textarea is labelled via htmlFor/id pair (required by AC-7).
 *   - The Analyse button reflects loading state with aria-busy.
 */

import React, { useCallback } from 'react'
import styles from './SuggestedDescriptionField.module.css'
import { PhotoAnalysisState } from './photoAnalysisStates.js'

/**
 * @param {{
 *   id?: string
 *   label?: string
 *   value: string
 *   onChange: (value: string) => void
 *   onSubmit?: (description: string) => void
 *   analysisState: import('./photoAnalysisStates.js').PhotoAnalysisState
 *   draft: import('./useAnalyzePhoto.js').AnalysisDraft | null
 *   onAnalyze: () => void
 *   onRecordDescription?: (description: string) => void
 *   errorCode?: string | null
 *   disabled?: boolean
 * }} props
 */
export function SuggestedDescriptionField({
    id = 'photo-description',
    label = 'Description',
    value,
    onChange,
    onSubmit,
    analysisState,
    draft,
    onAnalyze,
    onRecordDescription,
    errorCode,
    disabled = false,
}) {
    const isLoading  = analysisState === PhotoAnalysisState.LOADING
    const hasDraft   = analysisState === PhotoAnalysisState.READY && draft != null
    const isDegraded = analysisState === PhotoAnalysisState.DEGRADED
    const isDisabled = analysisState === PhotoAnalysisState.DISABLED

    const handleChange = useCallback((e) => {
        onChange(e.target.value)
    }, [onChange])

    const handleAnalyze = useCallback(() => {
        if (!isLoading && !isDisabled) onAnalyze()
    }, [onAnalyze, isLoading, isDisabled])

    const handleSubmit = useCallback((e) => {
        e.preventDefault()
        if (onRecordDescription) onRecordDescription(value)
        if (onSubmit) onSubmit(value)
    }, [onRecordDescription, onSubmit, value])

    return (
        <div className={styles.root}>
            <div className={styles.labelRow}>
                <label htmlFor={id} className={styles.label}>{label}</label>
                {!isDisabled && (
                    <button
                        type="button"
                        className={styles.analyzeBtn}
                        onClick={handleAnalyze}
                        disabled={disabled || isLoading}
                        aria-busy={isLoading}
                    >
                        {isLoading ? 'Analysing…' : 'Analyse photo'}
                    </button>
                )}
            </div>

            {hasDraft && (
                <div className={styles.advisoryChip} role="status" aria-live="polite">
                    <span className={styles.aiIcon} aria-hidden="true">&#10024;</span>
                    AI suggestion &mdash; advisory only, please review and edit
                </div>
            )}

            <textarea
                id={id}
                className={styles.textarea}
                value={value}
                onChange={handleChange}
                disabled={disabled}
                rows={4}
                aria-describedby={hasDraft ? `${id}-advisory` : undefined}
            />

            {hasDraft && (
                <p id={`${id}-advisory`} className={styles.advisoryNote}>
                    The text above was pre-filled by AI analysis. It is editable — your final
                    text is what will be saved.
                </p>
            )}

            {isDegraded && (
                <p className={styles.degradedNote} role="alert">
                    {errorCode === 'AI_DAILY_LIMIT_REACHED'
                        ? 'Daily analysis limit reached. You can still enter a description manually.'
                        : 'Photo analysis is temporarily unavailable. You can still enter a description manually.'}
                </p>
            )}
        </div>
    )
}

export default SuggestedDescriptionField
