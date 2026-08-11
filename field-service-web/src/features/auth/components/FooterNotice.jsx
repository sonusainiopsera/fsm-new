/**
 * @fileoverview FooterNotice — legal / support copy below the sign-in form.
 */
export function FooterNotice() {
  return (
    <p
      data-component="footer-notice"
      style={{
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-12)',
        color: 'var(--token-text-secondary)',
        textAlign: 'center',
        margin: 0,
      }}
    >
      By signing in you agree to the{' '}
      <a
        href="/terms"
        style={{ color: 'var(--token-accent-default)' }}
      >
        Terms of Service
      </a>{' '}
      and{' '}
      <a
        href="/privacy"
        style={{ color: 'var(--token-accent-default)' }}
      >
        Privacy Policy
      </a>
      .
    </p>
  )
}
