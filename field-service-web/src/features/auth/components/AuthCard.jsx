/**
 * @fileoverview AuthCard — the white card surface containing the sign-in form.
 *
 * @param {{ children: import('react').ReactNode }} props
 */
export function AuthCard({ children }) {
  return (
    <div
      data-component="auth-card"
      style={{
        background: 'var(--token-surface-raised)',
        borderRadius: 'var(--token-radius-large)',
        boxShadow: 'var(--token-elevation-2)',
        padding: 'var(--token-space-8)',
        width: '100%',
        maxWidth: '400px',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-4)',
      }}
    >
      {children}
    </div>
  )
}
