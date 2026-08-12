/**
 * SuggestedDescriptionField
 *
 * An editable textarea pre-filled with an AI-suggested description.
 * Shows a visible "AI suggested" chip (BR-30) and a one-tap clear action.
 *
 * Rendering safety:
 *   - Suggestion is inserted as a controlled value (never innerHTML).
 *   - No auto-submit: the field only changes on user action.
 *   - Clear action removes the chip and empties the field.
 *
 * Touch target compliance:
 *   - Clear button is min 44×44 px at 360 px viewport (light and dark appearance).
 *
 * @module features/photoanalysis/SuggestedDescriptionField
 */

import { useState, useCallback } from 'react';
import {
  SUGGESTED,
  EDITING,
  DISCARDED,
  STATES_WITH_SUGGESTION,
} from './photoAnalysisStates.js';

/**
 * @param {object} props
 * @param {string}   props.state          - current photoAnalysis state
 * @param {string}   [props.suggestion]   - AI-suggested text (advisory only)
 * @param {string}   [props.value]        - controlled textarea value
 * @param {Function} props.onChange       - called with new string when user edits
 * @param {Function} props.onClear        - called when user clears the suggestion
 * @param {boolean}  [props.disabled]     - disables the field
 * @param {string}   [props.placeholder]  - placeholder text
 */
export function SuggestedDescriptionField({
  state,
  suggestion,
  value,
  onChange,
  onClear,
  disabled = false,
  placeholder = 'Describe the fault or completed work…',
}) {
  const showChip = STATES_WITH_SUGGESTION.has(state) && suggestion != null;

  const handleChange = useCallback(
    (e) => {
      if (onChange) onChange(e.target.value);
    },
    [onChange]
  );

  const handleClear = useCallback(() => {
    if (onClear) onClear();
  }, [onClear]);

  return (
    <div
      style={{
        display: 'flex',
        flexDirection: 'column',
        gap: '8px',
        width: '100%',
      }}
    >
      {showChip && (
        <div
          style={{
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            gap: '8px',
          }}
        >
          {/* AI attribution chip (BR-30) */}
          <span
            role="status"
            aria-label="AI suggested description"
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: '4px',
              padding: '4px 10px',
              borderRadius: '12px',
              fontSize: '0.75rem',
              fontWeight: 600,
              background: 'var(--color-ai-chip-bg, #EEF2FF)',
              color: 'var(--color-ai-chip-text, #4F46E5)',
              border: '1px solid var(--color-ai-chip-border, #C7D2FE)',
            }}
          >
            {/* Shield-star icon — purely decorative */}
            <span aria-hidden="true">✦</span>
            AI suggested
          </span>

          {/* One-tap clear action — min 44×44 touch target */}
          <button
            type="button"
            onClick={handleClear}
            aria-label="Clear AI suggestion"
            disabled={disabled}
            style={{
              minWidth: '44px',
              minHeight: '44px',
              padding: '0 12px',
              display: 'inline-flex',
              alignItems: 'center',
              justifyContent: 'center',
              background: 'transparent',
              border: '1px solid var(--color-border-subtle, #D1D5DB)',
              borderRadius: '6px',
              cursor: disabled ? 'not-allowed' : 'pointer',
              fontSize: '0.75rem',
              color: 'var(--color-text-secondary, #6B7280)',
            }}
          >
            Clear
          </button>
        </div>
      )}

      <p
        style={{
          margin: 0,
          fontSize: '0.75rem',
          color: 'var(--color-text-secondary, #6B7280)',
          fontStyle: 'italic',
        }}
      >
        {showChip
          ? 'This is an AI suggestion — advisory only. Your description is what is recorded.'
          : 'Enter a description of the fault or completed work.'}
      </p>

      <textarea
        value={value ?? ''}
        onChange={handleChange}
        disabled={disabled}
        placeholder={placeholder}
        rows={4}
        aria-label="Work order description"
        aria-describedby={showChip ? 'ai-suggestion-notice' : undefined}
        style={{
          width: '100%',
          minHeight: '100px',
          padding: '10px 12px',
          borderRadius: '6px',
          border: '1px solid var(--color-border, #D1D5DB)',
          fontSize: '1rem',
          lineHeight: '1.5',
          resize: 'vertical',
          boxSizing: 'border-box',
          background: disabled
            ? 'var(--color-input-disabled-bg, #F9FAFB)'
            : 'var(--color-input-bg, #FFFFFF)',
          color: 'var(--color-input-text, #111827)',
        }}
      />
    </div>
  );
}

export default SuggestedDescriptionField;
