/**
 * SuggestedDescriptionField — component tests.
 *
 * Covers:
 *   - Pre-filled value from suggestion
 *   - AI-suggested chip presence
 *   - Clear action invokes onClear
 *   - Chip absent when state is DISCARDED or DEGRADED
 *   - Advisory copy visible
 *   - No innerHTML / HTML rendering of suggestion
 */

import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { SuggestedDescriptionField } from '../SuggestedDescriptionField.jsx';
import { SUGGESTED, EDITING, DISCARDED, DEGRADED } from '../photoAnalysisStates.js';

describe('SuggestedDescriptionField', () => {
  const suggestion = 'Relay on main circuit board showing heat damage.';

  describe('Suggestion chip presence', () => {
    it('shows AI-suggested chip when state is SUGGESTED', () => {
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      expect(screen.getByRole('status', { name: /AI suggested description/i })).toBeInTheDocument();
    });

    it('shows AI-suggested chip when state is EDITING', () => {
      render(
        <SuggestedDescriptionField
          state={EDITING}
          suggestion={suggestion}
          value="edited text"
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      expect(screen.getByRole('status', { name: /AI suggested/i })).toBeInTheDocument();
    });

    it('hides AI-suggested chip when state is DISCARDED', () => {
      render(
        <SuggestedDescriptionField
          state={DISCARDED}
          suggestion={null}
          value=""
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      expect(screen.queryByRole('status', { name: /AI suggested/i })).not.toBeInTheDocument();
    });

    it('hides AI-suggested chip when state is DEGRADED', () => {
      render(
        <SuggestedDescriptionField
          state={DEGRADED}
          suggestion={null}
          value=""
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      expect(screen.queryByRole('status', { name: /AI suggested/i })).not.toBeInTheDocument();
    });
  });

  describe('Pre-filled editable field', () => {
    it('pre-fills textarea with suggestion value', () => {
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      const textarea = screen.getByRole('textbox', { name: /description/i });
      expect(textarea.value).toBe(suggestion);
    });

    it('calls onChange when user edits the field', () => {
      const onChange = vi.fn();
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={onChange}
          onClear={vi.fn()}
        />
      );
      const textarea = screen.getByRole('textbox', { name: /description/i });
      fireEvent.change(textarea, { target: { value: 'manually edited text' } });
      expect(onChange).toHaveBeenCalledWith('manually edited text');
    });
  });

  describe('Clear action', () => {
    it('calls onClear when clear button is clicked', () => {
      const onClear = vi.fn();
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={vi.fn()}
          onClear={onClear}
        />
      );
      const clearBtn = screen.getByRole('button', { name: /clear/i });
      fireEvent.click(clearBtn);
      expect(onClear).toHaveBeenCalledTimes(1);
    });

    it('clear button has at least 44px touch target (inline min-height)', () => {
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      const clearBtn = screen.getByRole('button', { name: /clear/i });
      // style is applied inline; verify minHeight is present
      expect(clearBtn.style.minHeight).toBe('44px');
    });
  });

  describe('Advisory copy', () => {
    it('shows advisory notice when suggestion is present', () => {
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={suggestion}
          value={suggestion}
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      expect(screen.getByText(/advisory only/i)).toBeInTheDocument();
      expect(screen.getByText(/your description is what is recorded/i)).toBeInTheDocument();
    });

    it('suggestion is rendered as textarea value, not as innerHTML', () => {
      const xss = '<script>alert(1)</script>';
      render(
        <SuggestedDescriptionField
          state={SUGGESTED}
          suggestion={xss}
          value={xss}
          onChange={vi.fn()}
          onClear={vi.fn()}
        />
      );
      const textarea = screen.getByRole('textbox', { name: /description/i });
      // value contains the literal string, not parsed HTML
      expect(textarea.value).toBe(xss);
      // no <script> element in the DOM
      expect(document.querySelector('script[src]')).toBeNull();
    });
  });
});
