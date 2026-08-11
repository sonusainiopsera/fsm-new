/**
 * @fileoverview PageHeader — title + optional breadcrumb + exactly one primary action.
 * Development-time error thrown when more than one primary action is supplied.
 */
import { Button } from '../Button/Button.jsx'

/**
 * @typedef {{
 *   label: string,
 *   href?: string,
 *   onClick?: () => void
 * }} BreadcrumbItem
 */

/**
 * @typedef {{
 *   label: string,
 *   onClick: () => void,
 *   disabled?: boolean
 * }} HeaderAction
 */

/**
 * @param {{
 *   title: string,
 *   breadcrumbs?: BreadcrumbItem[],
 *   primaryAction?: HeaderAction,
 *   secondaryActions?: HeaderAction[],
 *   primaryActions?: HeaderAction[]
 * }} props
 */
export function PageHeader({ title, breadcrumbs, primaryAction, secondaryActions = [], primaryActions }) {
  // Development-time contract: reject multiple primary actions
  if (primaryActions && primaryActions.length > 1) {
    throw new Error(
      `[PageHeader] Only one primary action is permitted per page header. ` +
      `Received ${primaryActions.length}. Additional actions must use secondaryActions.`
    )
  }
  const resolvedPrimary = primaryAction ?? (primaryActions && primaryActions[0])

  return (
    <header
      role="banner"
      style={{
        padding: 'var(--token-space-4) var(--token-gutter)',
        background: 'var(--token-surface-card)',
        borderBottom: 'var(--token-elevation-border)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-2)',
      }}
    >
      {breadcrumbs && breadcrumbs.length > 0 && (
        <nav aria-label="Breadcrumb">
          <ol style={{ display: 'flex', gap: 'var(--token-space-2)', listStyle: 'none', margin: 0, padding: 0 }}>
            {breadcrumbs.map((crumb, i) => (
              <li key={i} style={{ display: 'flex', alignItems: 'center', gap: 'var(--token-space-2)' }}>
                {i > 0 && (
                  <span aria-hidden="true" style={{ color: 'var(--token-text-disabled)' }}>/</span>
                )}
                {crumb.href || crumb.onClick ? (
                  <a
                    href={crumb.href}
                    onClick={crumb.onClick}
                    style={{
                      fontSize: 'var(--token-fs-13)',
                      color: 'var(--token-text-secondary)',
                      textDecoration: 'none',
                    }}
                  >
                    {crumb.label}
                  </a>
                ) : (
                  <span style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)' }}>
                    {crumb.label}
                  </span>
                )}
              </li>
            ))}
          </ol>
        </nav>
      )}

      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 'var(--token-space-4)' }}>
        <h1
          style={{
            fontSize: 'var(--token-fs-24)',
            fontFamily: 'var(--token-family-base)',
            color: 'var(--token-text-primary)',
            margin: 0,
            letterSpacing: 'var(--token-ls-tight)',
          }}
        >
          {title}
        </h1>

        {(resolvedPrimary || secondaryActions.length > 0) && (
          <div style={{ display: 'flex', gap: 'var(--token-space-2)', alignItems: 'center' }}>
            {secondaryActions.map((action, i) => (
              <Button
                key={i}
                variant="ghost"
                onClick={action.onClick}
                disabled={action.disabled}
              >
                {action.label}
              </Button>
            ))}
            {resolvedPrimary && (
              <Button
                variant="primary"
                onClick={resolvedPrimary.onClick}
                disabled={resolvedPrimary.disabled}
              >
                {resolvedPrimary.label}
              </Button>
            )}
          </div>
        )}
      </div>
    </header>
  )
}
