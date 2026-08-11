import React from 'react';

import styles from './PageHeader.module.css';

/**
 * @typedef {{ label: string, href?: string }} BreadcrumbItem
 */

/**
 * Page header enforcing exactly one primary action.
 * Additional actions are accepted as secondaryActions and rendered as ghost/tertiary.
 *
 * @param {{
 *   title: string,
 *   breadcrumb?: BreadcrumbItem[],
 *   primaryAction?: React.ReactNode,
 *   secondaryActions?: React.ReactNode[],
 * }} props
 */
export function PageHeader({ title, breadcrumb = [], primaryAction, secondaryActions = [] }) {
  if (process.env.NODE_ENV !== 'production') {
    const primaryCount = React.Children.toArray(primaryAction).length;
    if (primaryCount > 1) {
      throw new Error(
        `PageHeader: exactly one primaryAction is allowed, but ${primaryCount} were provided. ` +
        'Additional actions must be passed as secondaryActions.'
      );
    }
  }

  return (
    <header className={styles.header}>
      {breadcrumb.length > 0 && (
        <nav aria-label="Breadcrumb">
          <ol className={styles.breadcrumb}>
            {breadcrumb.map((item, i) => {
              const isLast = i === breadcrumb.length - 1;
              return (
                <li key={i}>
                  {i > 0 && <span className={styles.breadcrumbSep} aria-hidden="true">/</span>}
                  {isLast ? (
                    <span className={styles.breadcrumbCurrent} aria-current="page">{item.label}</span>
                  ) : (
                    <a href={item.href ?? '#'}>{item.label}</a>
                  )}
                </li>
              );
            })}
          </ol>
        </nav>
      )}

      <div className={styles.titleRow}>
        <h1 className={styles.title}>{title}</h1>
        <div className={styles.actions}>
          {secondaryActions.map((action, i) => (
            <React.Fragment key={i}>{action}</React.Fragment>
          ))}
          {primaryAction}
        </div>
      </div>
    </header>
  );
}
