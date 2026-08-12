/**
 * PartsRowsList — repeatable parts consumption rows for the log-work screen.
 *
 * Each row lets the technician pick a part from their vehicle stock and enter
 * a quantity bounded by quantityOnHand. Parts are fetched from
 * GET /api/v1/technicians/me/stock.
 *
 * Shortfall responses (422 INSUFFICIENT_STOCK) are rendered per-row, naming
 * the part and shortfall quantity. A primary shortfall action opens
 * HoldReasonSheet with AWAITING_PARTS preselected.
 *
 * @module app/technician/components/PartsRowsList
 */

import React, { useCallback } from 'react';
import { useQuery } from '@tanstack/react-query';
import { apiFetch } from '../../../api/http.js';
import styles from './PartsRowsList.module.css';

/**
 * @typedef {{
 *   partId: string,
 *   partCode: string,
 *   partName: string,
 *   quantityOnHand: number,
 *   locationId: string,
 * }} VehicleStockLine
 *
 * @typedef {{
 *   rowId: string,
 *   partId: string | null,
 *   quantity: string,
 *   error: string | null,
 * }} PartsRow
 */

/**
 * Creates a new empty row.
 * @returns {PartsRow}
 */
export function createEmptyRow() {
  return { rowId: crypto.randomUUID(), partId: null, quantity: '1', error: null };
}

/**
 * Reducer for the parts row list.
 * @param {PartsRow[]} rows
 * @param {{ type: string, payload?: any }} action
 * @returns {PartsRow[]}
 */
export function partsRowReducer(rows, action) {
  switch (action.type) {
    case 'ADD_ROW':
      return [...rows, createEmptyRow()];

    case 'SET_PART': {
      const { rowId, partId } = action.payload;
      return rows.map((r) => r.rowId === rowId ? { ...r, partId, error: null } : r);
    }

    case 'SET_QUANTITY': {
      const { rowId, quantity } = action.payload;
      return rows.map((r) => r.rowId === rowId ? { ...r, quantity, error: null } : r);
    }

    case 'SET_ERROR': {
      const { rowId, error } = action.payload;
      return rows.map((r) => r.rowId === rowId ? { ...r, error } : r);
    }

    case 'REMOVE_ROW':
      return rows.filter((r) => r.rowId !== action.payload.rowId);

    case 'CLEAR_ERRORS':
      return rows.map((r) => ({ ...r, error: null }));

    default:
      return rows;
  }
}

/**
 * Formats the shortfall message for a 422 INSUFFICIENT_STOCK field error.
 *
 * @param {string} partCode
 * @param {string} fieldErrorMessage  Server message e.g. "requested 3, available 2"
 * @returns {string}
 */
export function buildShortfallMessage(partCode, fieldErrorMessage) {
  return `${partCode}: ${fieldErrorMessage}`;
}

/**
 * @param {{
 *   rows: PartsRow[],
 *   dispatch: (action: object) => void,
 *   onShortfall: (partCode: string, shortfallDetail: string) => void,
 *   isPending: boolean,
 * }} props
 */
export function PartsRowsList({ rows, dispatch, onShortfall, isPending }) {
  const { data: stockData, isLoading: stockLoading } = useQuery({
    queryKey: ['technician', 'stock'],
    queryFn: () => apiFetch('/technicians/me/stock?size=100'),
    staleTime: 60_000,
  });

  const stockLines = stockData?.data ?? [];

  const handlePartChange = useCallback((rowId, partId) => {
    dispatch({ type: 'SET_PART', payload: { rowId, partId } });
  }, [dispatch]);

  const handleQuantityChange = useCallback((rowId, quantity) => {
    dispatch({ type: 'SET_QUANTITY', payload: { rowId, quantity } });
  }, [dispatch]);

  const handleRemove = useCallback((rowId) => {
    dispatch({ type: 'REMOVE_ROW', payload: { rowId } });
  }, [dispatch]);

  function getStockLine(partId) {
    return stockLines.find((s) => s.partId === partId) ?? null;
  }

  function validateRow(row) {
    const qty = parseInt(row.quantity, 10);
    if (!row.partId) return 'Please select a part.';
    if (isNaN(qty) || qty < 1) return 'Quantity must be at least 1.';
    const stock = getStockLine(row.partId);
    if (stock && qty > stock.quantityOnHand) {
      return `Only ${stock.quantityOnHand} available on your vehicle.`;
    }
    return null;
  }

  return (
    <section className={styles.section} aria-label="Parts to log">
      <div className={styles.header}>
        <h2 className={styles.heading}>Parts consumed</h2>
        <button
          type="button"
          className={styles.addBtn}
          onClick={() => dispatch({ type: 'ADD_ROW' })}
          disabled={isPending}
          aria-label="Add parts row"
        >
          + Add part
        </button>
      </div>

      {rows.length === 0 && (
        <p className={styles.empty}>No parts to log. Tap "+ Add part" if parts were used.</p>
      )}

      {rows.map((row, idx) => {
        const stock = getStockLine(row.partId);
        const clientError = validateRow(row);
        const displayError = row.error ?? (clientError && row.partId ? clientError : null);

        return (
          <div key={row.rowId} className={styles.row} role="group" aria-label={`Parts row ${idx + 1}`}>
            <div className={styles.rowFields}>
              <select
                className={`${styles.select} ${displayError ? styles.inputError : ''}`}
                value={row.partId ?? ''}
                onChange={(e) => handlePartChange(row.rowId, e.target.value || null)}
                disabled={isPending || stockLoading}
                aria-label="Select part"
                aria-invalid={!!displayError}
              >
                <option value="">Select part…</option>
                {stockLines.map((s) => (
                  <option key={s.partId} value={s.partId}>
                    {s.partCode} — {s.partName} (on hand: {s.quantityOnHand})
                  </option>
                ))}
              </select>

              <input
                type="number"
                className={`${styles.qtyInput} ${displayError ? styles.inputError : ''}`}
                value={row.quantity}
                min={1}
                max={stock?.quantityOnHand ?? 999}
                onChange={(e) => handleQuantityChange(row.rowId, e.target.value)}
                disabled={isPending}
                aria-label="Quantity"
                aria-invalid={!!displayError}
              />

              <button
                type="button"
                className={styles.removeBtn}
                onClick={() => handleRemove(row.rowId)}
                disabled={isPending}
                aria-label="Remove row"
              >
                ✕
              </button>
            </div>

            {displayError && (
              <div className={styles.rowError} role="alert">
                <p className={styles.errorText}>{displayError}</p>
                {row.error && (
                  <button
                    type="button"
                    className={styles.holdBtn}
                    onClick={() => onShortfall(stock?.partCode ?? 'Unknown', row.error)}
                  >
                    Place on hold (awaiting parts)
                  </button>
                )}
              </div>
            )}
          </div>
        );
      })}
    </section>
  );
}
