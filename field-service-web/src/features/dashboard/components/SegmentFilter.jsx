import React from 'react';
import styles from './SegmentFilter.module.css';

const PRIORITY_OPTIONS = [
  { value: '', label: 'All priorities' },
  { value: 'URGENT', label: 'Urgent' },
  { value: 'HIGH',   label: 'High' },
  { value: 'NORMAL', label: 'Normal' },
  { value: 'LOW',    label: 'Low' },
];

/**
 * Segment filter bar — priority and team selectors, URL-synchronized.
 *
 * @param {{
 *   priority: string,
 *   team: string,
 *   onPriorityChange: (priority: string) => void,
 *   onTeamChange: (team: string) => void,
 * }} props
 */
export function SegmentFilter({ priority, team, onPriorityChange, onTeamChange }) {
  return (
    <div className={styles.bar}>
      <label className={styles.label} htmlFor="seg-priority">
        Priority
        <select
          id="seg-priority"
          className={styles.select}
          value={priority}
          onChange={(e) => onPriorityChange(e.target.value)}
        >
          {PRIORITY_OPTIONS.map((o) => (
            <option key={o.value} value={o.value}>{o.label}</option>
          ))}
        </select>
      </label>

      <label className={styles.label} htmlFor="seg-team">
        Team
        <input
          id="seg-team"
          type="text"
          className={styles.input}
          value={team}
          placeholder="All teams"
          aria-label="Filter by team"
          onChange={(e) => onTeamChange(e.target.value)}
        />
      </label>
    </div>
  );
}
