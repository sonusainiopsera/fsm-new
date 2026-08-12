import React from 'react';
import styles from './WidgetGrid.module.css';

/**
 * Responsive CSS grid for KPI widget cards.
 *
 * @param {{ children: React.ReactNode }} props
 */
export function WidgetGrid({ children }) {
  return (
    <div className={styles.grid} role="list">
      {React.Children.map(children, (child) =>
        child ? <div className={styles.item} role="listitem">{child}</div> : null,
      )}
    </div>
  );
}
