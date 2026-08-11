import React, { useState } from 'react';

import { useDensity } from '../../density/DensityContext.js';

import { useResponsiveTableMode } from './useResponsiveTableMode.js';
import styles from './DataTable.module.css';

/**
 * @typedef {{
 *   key: string,
 *   header: string,
 *   align?: 'left' | 'right' | 'center',
 *   sortable?: boolean,
 *   numeric?: boolean,
 *   render?: (value: unknown, row: object) => React.ReactNode,
 * }} ColumnDef
 */

/**
 * @typedef {{ field: string, direction: 'asc' | 'desc' }} SortState
 */

/**
 * Headless-column DataTable. Responsive: table above 768 px, stacked cards below.
 * Defaults to 40 px comfortable rows (32 px compact). Sticky header, hairline separators,
 * no zebra striping, right-aligned numerics, 2 px accent inset on selected row.
 *
 * @param {{
 *   columns: ColumnDef[],
 *   data: object[],
 *   rowKey?: (row: object) => string,
 *   selectedKey?: string | null,
 *   onRowClick?: (row: object) => void,
 *   onSort?: (sort: SortState) => void,
 *   sort?: SortState | null,
 *   rowActions?: (row: object) => React.ReactNode,
 *   emptyState?: React.ReactNode,
 *   caption?: string,
 * }} props
 */
export function DataTable({
  columns,
  data,
  rowKey = (row) => row.id ?? String(Math.random()),
  selectedKey = null,
  onRowClick,
  onSort,
  sort = null,
  rowActions,
  emptyState,
  caption,
}) {
  const { density, setDensity } = useDensity();
  const { mode, containerRef } = useResponsiveTableMode();

  function handleSort(col) {
    if (!col.sortable || !onSort) return;
    const newDirection =
      sort?.field === col.key && sort?.direction === 'asc' ? 'desc' : 'asc';
    onSort({ field: col.key, direction: newDirection });
  }

  function getSortIcon(col) {
    if (!col.sortable) return null;
    if (sort?.field === col.key) {
      return sort.direction === 'asc' ? '↑' : '↓';
    }
    return '↕';
  }

  if (mode === 'cards') {
    return (
      <div ref={containerRef} className={styles.wrapper}>
        <div className={styles.toolbar}>
          <DensityToggle density={density} setDensity={setDensity} />
        </div>
        <div className={styles.cards} role="list">
          {data.length === 0 && emptyState
            ? <div role="listitem">{emptyState}</div>
            : data.map((row) => {
                const key = rowKey(row);
                const isSelected = selectedKey === key;
                return (
                  <div
                    key={key}
                    role="listitem"
                    className={[styles.card, isSelected ? styles.cardSelected : ''].filter(Boolean).join(' ')}
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                    tabIndex={onRowClick ? 0 : undefined}
                    onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(row); } } : undefined}
                    aria-selected={isSelected || undefined}
                  >
                    {columns.map((col) => (
                      <div key={col.key} className={styles.cardRow}>
                        <span className={styles.cardFieldLabel}>{col.header}</span>
                        <span className={[styles.cardFieldValue, col.numeric ? 'numeric' : ''].filter(Boolean).join(' ')}>
                          {col.render ? col.render(row[col.key], row) : String(row[col.key] ?? '')}
                        </span>
                      </div>
                    ))}
                    {rowActions && (
                      <div className={styles.cardActions}>{rowActions(row)}</div>
                    )}
                  </div>
                );
              })
          }
        </div>
      </div>
    );
  }

  return (
    <div ref={containerRef} className={[styles.wrapper, density === 'compact' ? styles.compact : ''].filter(Boolean).join(' ')}>
      <div className={styles.toolbar}>
        <DensityToggle density={density} setDensity={setDensity} />
      </div>
      <div className={styles.tableScroll}>
        <table className={styles.table} aria-sort={sort ? `${sort.direction}ending` : undefined}>
          {caption && <caption className="sr-only">{caption}</caption>}
          <thead className={styles.thead}>
            <tr>
              {columns.map((col) => {
                const isNumeric = col.numeric || col.align === 'right';
                const isSortActive = sort?.field === col.key;
                return (
                  <th
                    key={col.key}
                    className={[styles.th, isNumeric ? styles.thNumeric : ''].filter(Boolean).join(' ')}
                    aria-sort={isSortActive ? (sort.direction === 'asc' ? 'ascending' : 'descending') : col.sortable ? 'none' : undefined}
                    scope="col"
                  >
                    {col.sortable ? (
                      <button
                        type="button"
                        className={styles.sortBtn}
                        onClick={() => handleSort(col)}
                        aria-label={`Sort by ${col.header}${isSortActive ? `, currently ${sort.direction}ending` : ''}`}
                      >
                        {col.header}
                        <span className={[styles.sortIcon, isSortActive ? styles.sortActive : ''].filter(Boolean).join(' ')} aria-hidden="true">
                          {getSortIcon(col)}
                        </span>
                      </button>
                    ) : col.header}
                  </th>
                );
              })}
              {rowActions && <th className={styles.th} scope="col"><span className="sr-only">Actions</span></th>}
            </tr>
          </thead>
          <tbody>
            {data.length === 0 ? (
              <tr>
                <td colSpan={columns.length + (rowActions ? 1 : 0)} className={styles.emptyCell}>
                  {emptyState ?? 'No data'}
                </td>
              </tr>
            ) : (
              data.map((row) => {
                const key = rowKey(row);
                const isSelected = selectedKey === key;
                return (
                  <tr
                    key={key}
                    className={[styles.tr, isSelected ? styles.trSelected : ''].filter(Boolean).join(' ')}
                    onClick={onRowClick ? () => onRowClick(row) : undefined}
                    tabIndex={onRowClick ? 0 : undefined}
                    onKeyDown={onRowClick ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onRowClick(row); } } : undefined}
                    aria-selected={isSelected || undefined}
                  >
                    {columns.map((col) => {
                      const isNumeric = col.numeric || col.align === 'right';
                      return (
                        <td
                          key={col.key}
                          className={[styles.td, isNumeric ? styles.tdNumeric : ''].filter(Boolean).join(' ')}
                          title={col.render ? undefined : String(row[col.key] ?? '')}
                        >
                          {col.render ? col.render(row[col.key], row) : String(row[col.key] ?? '')}
                        </td>
                      );
                    })}
                    {rowActions && <td className={styles.td}>{rowActions(row)}</td>}
                  </tr>
                );
              })
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}

/**
 * Density toggle control for DataTable.
 * @param {{ density: string, setDensity: (d: string) => void }} props
 */
function DensityToggle({ density, setDensity }) {
  return (
    <div className={styles.densityToggle} role="group" aria-label="Row density">
      <button
        type="button"
        className={[styles.densityBtn, density === 'comfortable' ? styles.densityBtnActive : ''].filter(Boolean).join(' ')}
        onClick={() => setDensity('comfortable')}
        aria-pressed={density === 'comfortable'}
        title="Comfortable rows"
      >
        Comfortable
      </button>
      <button
        type="button"
        className={[styles.densityBtn, density === 'compact' ? styles.densityBtnActive : ''].filter(Boolean).join(' ')}
        onClick={() => setDensity('compact')}
        aria-pressed={density === 'compact'}
        title="Compact rows"
      >
        Compact
      </button>
    </div>
  );
}
