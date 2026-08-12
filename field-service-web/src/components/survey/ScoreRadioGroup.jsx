/**
 * ScoreRadioGroup — accessible radio group for 1-to-N integer scores.
 *
 * Renders each score option as a visible, labelled radio button.
 * Keyboard: arrow keys move between options, space/enter select.
 *
 * @param {{
 *   name: string,
 *   min?: number,
 *   max?: number,
 *   value: number | null,
 *   onChange: (score: number) => void,
 *   disabled?: boolean,
 *   lowLabel?: string,
 *   highLabel?: string,
 *   id?: string,
 * }} props
 */

import React from 'react';
import styles from './ScoreRadioGroup.module.css';

export function ScoreRadioGroup({
  name,
  min = 1,
  max = 5,
  value,
  onChange,
  disabled = false,
  lowLabel = 'Very dissatisfied',
  highLabel = 'Very satisfied',
  id,
}) {
  const scores = [];
  for (let i = min; i <= max; i++) scores.push(i);

  return (
    <div
      id={id}
      className={styles.group}
      role="radiogroup"
      aria-label={`Score from ${min} to ${max}`}
    >
      <div className={styles.options}>
        {scores.map((score) => {
          const inputId = `${name}-${score}`;
          const checked = value === score;
          return (
            <label
              key={score}
              htmlFor={inputId}
              className={[
                styles.option,
                checked ? styles.optionSelected : '',
                disabled ? styles.optionDisabled : '',
              ].filter(Boolean).join(' ')}
            >
              <input
                id={inputId}
                type="radio"
                name={name}
                value={String(score)}
                checked={checked}
                onChange={() => onChange(score)}
                disabled={disabled}
                className={styles.input}
                aria-label={`${score} — ${_scoreLabel(score, min, max, lowLabel, highLabel)}`}
              />
              <span className={styles.scoreNum} aria-hidden="true">{score}</span>
            </label>
          );
        })}
      </div>
      <div className={styles.legend} aria-hidden="true">
        <span className={styles.legendLow}>{lowLabel}</span>
        <span className={styles.legendHigh}>{highLabel}</span>
      </div>
    </div>
  );
}

function _scoreLabel(score, min, max, lowLabel, highLabel) {
  if (score === min) return lowLabel;
  if (score === max) return highLabel;
  return `${score} out of ${max}`;
}
