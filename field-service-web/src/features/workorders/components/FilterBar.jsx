/**
 * FilterBar — dispatcher board filter controls.
 *
 * Exposes: state (multi-select), priority (multi-select), technician,
 * customer, date-from, date-to, and at-risk toggle. All state is
 * synchronised to URL search params via the setFilter callback so a
 * filtered board is shareable and survives reload.
 *
 * Filter changes are debounced (300 ms) before the URL is updated to
 * avoid a request storm on rapid key-presses.
 *
 * @module features/workorders/components/FilterBar
 */

import React, { useEffect, useRef, useState } from 'react';

import styles from './FilterBar.module.css';

/** @type {string[]} */
const ALL_STATES = ['NEW', 'ASSIGNED', 'EN_ROUTE', 'IN_PROGRESS', 'ON_HOLD', 'COMPLETED', 'CLOSED', 'CANCELLED'];
const ALL_PRIORITIES = ['URGENT', 'HIGH', 'NORMAL', 'LOW'];

/**
 * @param {{
 *   filters: import('../api/useWorkOrderSearch.js').BoardFilters,
 *   setFilter: (key: string, value: unknown) => void,
 * }} props
 */
export function FilterBar({ filters, setFilter }) {
  // Debounced text fields: technicianId and customerId
  const [techInput,     setTechInput]     = useState(filters.technicianId ?? '');
  const [customerInput, setCustomerInput] = useState(filters.customerId   ?? '');

  const techTimer     = useRef(null);
  const customerTimer = useRef(null);

  // Sync if URL state changes externally (e.g. back navigation)
  useEffect(() => { setTechInput(filters.technicianId ?? ''); }, [filters.technicianId]);
  useEffect(() => { setCustomerInput(filters.customerId ?? ''); }, [filters.customerId]);

  function handleTechChange(e) {
    const v = e.target.value;
    setTechInput(v);
    clearTimeout(techTimer.current);
    techTimer.current = setTimeout(() => setFilter('technicianId', v || null), 300);
  }

  function handleCustomerChange(e) {
    const v = e.target.value;
    setCustomerInput(v);
    clearTimeout(customerTimer.current);
    customerTimer.current = setTimeout(() => setFilter('customerId', v || null), 300);
  }

  function toggleState(s) {
    const next = filters.states.includes(s)
      ? filters.states.filter((x) => x !== s)
      : [...filters.states, s];
    setFilter('states', next);
  }

  function togglePriority(p) {
    const next = filters.priorities.includes(p)
      ? filters.priorities.filter((x) => x !== p)
      : [...filters.priorities, p];
    setFilter('priorities', next);
  }

  function clearAll() {
    setFilter('states',      []);
    setFilter('priorities',  []);
    setFilter('technicianId', null);
    setFilter('customerId',   null);
    setFilter('dateFrom',     null);
    setFilter('dateTo',       null);
    setFilter('atRisk',       false);
    setTechInput('');
    setCustomerInput('');
  }

  const hasFilters =
    filters.states.length > 0 ||
    filters.priorities.length > 0 ||
    filters.technicianId ||
    filters.customerId ||
    filters.dateFrom ||
    filters.dateTo ||
    filters.atRisk;

  return (
    <div className={styles.bar} role="search" aria-label="Work order filters">
      {/* State multi-select */}
      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>State</legend>
        <div className={styles.chips}>
          {ALL_STATES.map((s) => (
            <button
              key={s}
              type="button"
              className={[styles.filterChip, filters.states.includes(s) ? styles.filterChipActive : ''].filter(Boolean).join(' ')}
              onClick={() => toggleState(s)}
              aria-pressed={filters.states.includes(s)}
              aria-label={`Filter by state: ${s.toLowerCase().replace(/_/g, ' ')}`}
            >
              {s.replace(/_/g, ' ')}
            </button>
          ))}
        </div>
      </fieldset>

      {/* Priority multi-select */}
      <fieldset className={styles.fieldset}>
        <legend className={styles.legend}>Priority</legend>
        <div className={styles.chips}>
          {ALL_PRIORITIES.map((p) => (
            <button
              key={p}
              type="button"
              className={[styles.filterChip, filters.priorities.includes(p) ? styles.filterChipActive : ''].filter(Boolean).join(' ')}
              onClick={() => togglePriority(p)}
              aria-pressed={filters.priorities.includes(p)}
              aria-label={`Filter by priority: ${p.toLowerCase()}`}
            >
              {p.charAt(0) + p.slice(1).toLowerCase()}
            </button>
          ))}
        </div>
      </fieldset>

      {/* Text filters */}
      <div className={styles.textFilters}>
        <label className={styles.label} htmlFor="filter-technician">
          Technician
          <input
            id="filter-technician"
            type="text"
            className={styles.input}
            placeholder="Filter by name or ID"
            value={techInput}
            onChange={handleTechChange}
            aria-label="Filter by technician"
          />
        </label>

        <label className={styles.label} htmlFor="filter-customer">
          Customer
          <input
            id="filter-customer"
            type="text"
            className={styles.input}
            placeholder="Filter by name or ID"
            value={customerInput}
            onChange={handleCustomerChange}
            aria-label="Filter by customer"
          />
        </label>

        <label className={styles.label} htmlFor="filter-date-from">
          Created from
          <input
            id="filter-date-from"
            type="date"
            className={styles.input}
            value={filters.dateFrom ?? ''}
            onChange={(e) => setFilter('dateFrom', e.target.value || null)}
            aria-label="Filter by created-from date"
          />
        </label>

        <label className={styles.label} htmlFor="filter-date-to">
          Created to
          <input
            id="filter-date-to"
            type="date"
            className={styles.input}
            value={filters.dateTo ?? ''}
            onChange={(e) => setFilter('dateTo', e.target.value || null)}
            aria-label="Filter by created-to date"
          />
        </label>
      </div>

      {/* At-risk toggle */}
      <label className={styles.toggleLabel}>
        <input
          type="checkbox"
          className={styles.toggleCheckbox}
          checked={filters.atRisk}
          onChange={(e) => setFilter('atRisk', e.target.checked)}
          aria-label="Show at-risk work orders only"
        />
        <span aria-hidden="true">⬥</span>
        {' '}At risk only
      </label>

      {/* Clear all */}
      {hasFilters && (
        <button
          type="button"
          className={styles.clearBtn}
          onClick={clearAll}
          aria-label="Clear all filters"
        >
          Clear all
        </button>
      )}
    </div>
  );
}
