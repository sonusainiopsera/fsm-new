/**
 * @fileoverview BrandPanel — left panel of the split sign-in layout.
 * Hidden on viewports narrower than 768 px (the on-screen-keyboard viewport; AC-11).
 */
export function BrandPanel() {
  return (
    <div
      data-component="brand-panel"
      aria-hidden="true"
      style={{
        background: 'var(--token-accent-subtle)',
        borderRadius: 'var(--token-radius-large)',
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 'var(--token-space-8)',
        gap: 'var(--token-space-4)',
      }}
    >
      <div
        style={{
          width: '64px',
          height: '64px',
          borderRadius: 'var(--token-radius-control)',
          background: 'var(--token-accent-default)',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
        }}
      >
        {/* Brand logo placeholder */}
        <span
          style={{
            color: 'var(--token-surface-base)',
            fontSize: 'var(--token-fs-24)',
            fontWeight: 700,
            fontFamily: 'var(--token-family-base)',
          }}
        >
          FS
        </span>
      </div>
      <p
        style={{
          fontFamily: 'var(--token-family-base)',
          fontSize: 'var(--token-fs-18)',
          fontWeight: 600,
          color: 'var(--token-text-primary)',
          textAlign: 'center',
          margin: 0,
        }}
      >
        Field Service
      </p>
      <p
        style={{
          fontFamily: 'var(--token-family-base)',
          fontSize: 'var(--token-fs-14)',
          color: 'var(--token-text-secondary)',
          textAlign: 'center',
          margin: 0,
        }}
      >
        Manage work orders, dispatch technicians, and track service delivery.
      </p>
    </div>
  )
}
