import React from 'react';

import { useDensity } from '../../density/DensityContext.js';

import styles from './Chip.module.css';

/**
 * @typedef {'urgent' | 'high' | 'normal' | 'low'} Priority
 * @typedef {'new' | 'assigned' | 'en_route' | 'in_progress' | 'on_hold' | 'completed' | 'closed' | 'cancelled'} WorkOrderState
 * @typedef {'high' | 'medium' | 'low'} RiskLevel
 * @typedef {'priority' | 'state' | 'risk'} ChipVariant
 */

const PRIORITY_META = {
  urgent:   { icon: '▲▲', shape: 'triangle-double', label: 'Urgent' },
  high:     { icon: '▲',  shape: 'triangle',        label: 'High' },
  normal:   { icon: '●',  shape: 'circle',           label: 'Normal' },
  low:      { icon: '▼',  shape: 'triangle-down',    label: 'Low' },
};

const STATE_META = {
  new:         { icon: '○', shape: 'circle-empty',    label: 'New' },
  assigned:    { icon: '◑', shape: 'circle-half',     label: 'Assigned' },
  en_route:    { icon: '→', shape: 'arrow',            label: 'En Route' },
  in_progress: { icon: '◉', shape: 'circle-dot',      label: 'In Progress' },
  on_hold:     { icon: '‖', shape: 'pause',            label: 'On Hold' },
  completed:   { icon: '✓', shape: 'check',            label: 'Completed' },
  closed:      { icon: '■', shape: 'square',           label: 'Closed' },
  cancelled:   { icon: '✕', shape: 'cross',            label: 'Cancelled' },
};

const RISK_META = {
  high:   { icon: '⬥', shape: 'diamond',       label: 'High Risk' },
  medium: { icon: '◆', shape: 'diamond-small',  label: 'Medium Risk' },
  low:    { icon: '◇', shape: 'diamond-empty',  label: 'Low Risk' },
};

/**
 * Status / priority / risk chip.
 * Meaning is conveyed via colour + text + icon shape (BR-34).
 * Unrecognised enum values render a neutral fallback with a console warning.
 *
 * @param {{
 *   variant: ChipVariant,
 *   value: string,
 *   label?: string,
 * }} props
 */
export function Chip({ variant, value, label }) {
  const { density } = useDensity();
  const normalized = value?.toLowerCase().replace(/[\s-]/g, '_') ?? '';

  let meta = null;
  let cssClass = styles.neutral;

  if (variant === 'priority') {
    meta = PRIORITY_META[normalized];
    if (meta) {
      cssClass = styles[`priority-${normalized}`];
    } else {
      console.warn(`Chip[priority]: unrecognised value "${value}" — rendering neutral fallback`);
    }
  } else if (variant === 'state') {
    meta = STATE_META[normalized];
    if (meta) {
      cssClass = styles[`state-${normalized}`];
    } else {
      console.warn(`Chip[state]: unrecognised value "${value}" — rendering neutral fallback`);
    }
  } else if (variant === 'risk') {
    meta = RISK_META[normalized];
    if (meta) {
      cssClass = styles[`risk-${normalized}`];
    } else {
      console.warn(`Chip[risk]: unrecognised value "${value}" — rendering neutral fallback`);
    }
  } else {
    console.warn(`Chip: unknown variant "${variant}"`);
  }

  const displayLabel = label ?? meta?.label ?? value;
  const icon = meta?.icon ?? '●';
  const shape = meta?.shape ?? 'circle';

  return (
    <span
      className={[
        styles.chip,
        cssClass,
        density === 'compact' ? styles.compact : '',
      ].filter(Boolean).join(' ')}
      role="status"
      aria-label={displayLabel}
    >
      <span className={styles.icon} aria-hidden="true" data-shape={shape}>
        {icon}
      </span>
      <span>{displayLabel}</span>
    </span>
  );
}
