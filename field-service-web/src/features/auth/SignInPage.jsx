/**
 * @fileoverview SignInPage — the single authentication entry point for all four personas.
 *
 * Component hierarchy (AC-1):
 *   SignInPage
 *     AppearanceToggleButton (top-right, 44 px touch target, AC-9/AC-10)
 *     .sign-in-layout (split: BrandPanel + AuthCard; hidden BrandPanel < 768 px, AC-11)
 *       BrandPanel
 *       AuthCard
 *         <form>
 *           GenericErrorAlert  (aria-live="assertive", shown on 401/429/503/network, AC-9)
 *           EmailInput         (FormField → <input type="email" autocomplete="email">)
 *           PasswordInput      (FormField → <input> + ShowPasswordToggle 44 px)
 *           RememberDeviceSwitch (inline, deferred — does not affect current API payload)
 *           ForgotPasswordLink (→ /forgot-password placeholder)
 *           SubmitButton       (disabled + loading text while isPending, AC-8)
 *           [SSO divider + SsoButton — behind VITE_SSO_ENABLED feature flag, AC-1]
 *         FooterNotice
 *
 * Boot-time silent refresh (AC-5):
 *   On mount, attempts refreshToken() from the HttpOnly cookie. Navigates away on
 *   success so a page reload does not force re-entry of credentials. Shows a loading
 *   indicator while the attempt is in flight.
 *
 * Double-submit prevention (AC-8):
 *   The submit button is disabled and shows "Signing in…" while isPending is true.
 *   handleSubmit returns early if isPending, so Enter-key presses are also blocked.
 *
 * SECURITY:
 *   - Token stored via tokenStore.setToken() (module scope, no persistence) — AC-3.
 *   - API error messages passed through verbatim — no account-existence inference — AC-6.
 */

import { useState, useEffect, useRef } from 'react'

import { useAppearance } from '../../appearance/AppearanceProvider.jsx'
import { Button, LoadingState } from '../../components/index.js'
import { AuthCard } from './components/AuthCard.jsx'
import { BrandPanel } from './components/BrandPanel.jsx'
import { EmailInput } from './components/EmailInput.jsx'
import { FooterNotice } from './components/FooterNotice.jsx'
import { GenericErrorAlert } from './components/GenericErrorAlert.jsx'
import { PasswordInput } from './components/PasswordInput.jsx'
import { useSignIn } from './useSignIn.js'

// Feature flag: SSO is deferred until the customer identity model is ratified.
// Set VITE_SSO_ENABLED=true in .env.local to enable during development.
const SSO_ENABLED = import.meta.env.VITE_SSO_ENABLED === 'true'

// ── Appearance toggle ─────────────────────────────────────────────────────────

/**
 * @returns {import('react').JSX.Element}
 */
function AppearanceToggleButton() {
  const { preference, setPreference } = useAppearance()
  const isDark = preference === 'DARK'

  function toggle() {
    setPreference(isDark ? 'LIGHT' : 'DARK')
  }

  return (
    <button
      type="button"
      aria-label={isDark ? 'Switch to light appearance' : 'Switch to dark appearance'}
      aria-pressed={isDark}
      onClick={toggle}
      style={{
        position: 'absolute',
        top: 'var(--token-space-4)',
        right: 'var(--token-space-4)',
        background: 'none',
        border: '1px solid var(--token-border-default)',
        borderRadius: 'var(--token-radius-control)',
        cursor: 'pointer',
        fontFamily: 'var(--token-family-base)',
        fontSize: 'var(--token-fs-13)',
        color: 'var(--token-text-secondary)',
        minHeight: '44px',
        minWidth: '44px',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
      }}
      data-testid="appearance-toggle"
    >
      {isDark ? '☀' : '☾'}
    </button>
  )
}

// ── Sign-in form ──────────────────────────────────────────────────────────────

/**
 * @returns {import('react').JSX.Element}
 */
export default function SignInPage() {
  const { signIn, isPending, error, reset, attemptBootRefresh } = useSignIn()

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [showPassword, setShowPassword] = useState(false)
  const [rememberDevice, setRememberDevice] = useState(false)
  // Start in boot-refresh state; cleared once the refresh attempt resolves
  const [bootRefreshing, setBootRefreshing] = useState(true)

  const hasAttemptedBoot = useRef(false)
  const errorRef = useRef(null)

  // Boot-time silent refresh (AC-5)
  useEffect(() => {
    if (hasAttemptedBoot.current) return
    hasAttemptedBoot.current = true
    attemptBootRefresh().finally(() => setBootRefreshing(false))
  }, [attemptBootRefresh])

  // Move focus to the error alert when it appears (AC-9: assistive tech receives the alert)
  useEffect(() => {
    if (error && errorRef.current) {
      errorRef.current.focus()
    }
  }, [error])

  function handleSubmit(e) {
    e.preventDefault()
    if (isPending) return // Double-submit guard (AC-8)
    reset() // Clear previous error before new attempt
    signIn({ email, password })
  }

  // Derive field-level errors from 400 responses (AC-7)
  const emailFieldErrors = error?.status === 400
    ? (error.fieldErrors?.filter(fe => fe.field === 'email') ?? [])
    : []
  const passwordFieldErrors = error?.status === 400
    ? (error.fieldErrors?.filter(fe => fe.field === 'password') ?? [])
    : []

  // Show generic alert for non-field errors (401, 429, 503, 0=network, 400 with no field errors)
  const hasFieldErrors = (emailFieldErrors.length + passwordFieldErrors.length) > 0
  const showGenericAlert = error !== null && !(error.status === 400 && hasFieldErrors)

  // While checking the boot refresh cookie, show loading
  if (bootRefreshing) {
    return (
      <div
        data-testid="sign-in-boot-loading"
        style={{
          minHeight: '100dvh',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          background: 'var(--token-surface-base)',
        }}
      >
        <LoadingState />
      </div>
    )
  }

  return (
    <div
      data-testid="sign-in-page"
      style={{
        minHeight: '100dvh',
        background: 'var(--token-surface-base)',
        position: 'relative',
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'center',
        padding: 'var(--token-space-4)',
        boxSizing: 'border-box',
      }}
    >
      <AppearanceToggleButton />

      {/* Split layout: brand panel (hidden < 768 px) + auth card (AC-11) */}
      <div
        data-testid="sign-in-layout"
        style={{
          display: 'grid',
          gridTemplateColumns: 'minmax(0, 1fr)',
          gap: 'var(--token-space-4)',
          width: '100%',
          maxWidth: '900px',
        }}
      >
        {/* BrandPanel hidden on narrow viewports via media query in a style tag */}
        <style>{`
          @media (min-width: 768px) {
            [data-sign-in-grid] {
              grid-template-columns: 1fr 1fr !important;
            }
            [data-brand-panel-wrapper] {
              display: flex !important;
            }
          }
        `}</style>
        <div
          data-sign-in-grid
          style={{
            display: 'grid',
            gridTemplateColumns: 'minmax(0, 1fr)',
            gap: 'var(--token-space-4)',
            width: '100%',
          }}
        >
          <div
            data-brand-panel-wrapper
            style={{ display: 'none' }}
          >
            <BrandPanel />
          </div>

          <div
            style={{
              display: 'flex',
              alignItems: 'center',
              justifyContent: 'center',
            }}
          >
            <AuthCard>
              <h1
                style={{
                  fontFamily: 'var(--token-family-base)',
                  fontSize: 'var(--token-fs-20)',
                  fontWeight: 600,
                  color: 'var(--token-text-primary)',
                  margin: 0,
                }}
              >
                Sign in
              </h1>

              {/* Generic error alert (aria-live, AC-9) */}
              {showGenericAlert && (
                <div
                  tabIndex={-1}
                  ref={errorRef}
                  style={{ outline: 'none' }}
                >
                  <GenericErrorAlert error={error} onDismiss={reset} />
                </div>
              )}

              <form onSubmit={handleSubmit} noValidate>
                <div
                  style={{
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 'var(--token-space-4)',
                  }}
                >
                  <EmailInput
                    value={email}
                    onChange={e => setEmail(e.target.value)}
                    errors={emailFieldErrors.map(fe => fe.message)}
                    disabled={isPending}
                  />

                  <PasswordInput
                    value={password}
                    onChange={e => setPassword(e.target.value)}
                    show={showPassword}
                    onToggleShow={() => setShowPassword(s => !s)}
                    errors={passwordFieldErrors.map(fe => fe.message)}
                    disabled={isPending}
                  />

                  {/* Remember device switch (deferred: does not affect login payload yet) */}
                  <label
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      gap: 'var(--token-space-2)',
                      fontFamily: 'var(--token-family-base)',
                      fontSize: 'var(--token-fs-14)',
                      color: 'var(--token-text-primary)',
                      cursor: 'pointer',
                      minHeight: '44px',
                    }}
                  >
                    <input
                      type="checkbox"
                      role="switch"
                      aria-checked={rememberDevice}
                      checked={rememberDevice}
                      onChange={e => setRememberDevice(e.target.checked)}
                      disabled={isPending}
                      style={{ cursor: 'pointer' }}
                    />
                    Remember this device
                  </label>

                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'flex-end',
                    }}
                  >
                    {/* Forgot-password: routed to placeholder until the flow is implemented */}
                    <a
                      href="/forgot-password"
                      style={{
                        fontFamily: 'var(--token-family-base)',
                        fontSize: 'var(--token-fs-14)',
                        color: 'var(--token-accent-default)',
                        minHeight: '44px',
                        display: 'inline-flex',
                        alignItems: 'center',
                      }}
                    >
                      Forgot password?
                    </a>
                  </div>

                  {/* Submit button — disables on isPending (AC-8) */}
                  <Button
                    type="submit"
                    variant="primary"
                    touchTarget
                    disabled={isPending}
                    aria-busy={isPending}
                    style={{ width: '100%' }}
                  >
                    {isPending ? 'Signing in…' : 'Sign in'}
                  </Button>

                  {/* SSO section — behind feature flag (deferred per unratified customer identity model) */}
                  {SSO_ENABLED && (
                    <>
                      <div
                        role="separator"
                        aria-hidden="true"
                        style={{
                          display: 'flex',
                          alignItems: 'center',
                          gap: 'var(--token-space-2)',
                          fontFamily: 'var(--token-family-base)',
                          fontSize: 'var(--token-fs-13)',
                          color: 'var(--token-text-secondary)',
                        }}
                      >
                        <hr style={{ flex: 1, border: 'none', borderTop: '1px solid var(--token-border-default)', margin: 0 }} />
                        <span>or</span>
                        <hr style={{ flex: 1, border: 'none', borderTop: '1px solid var(--token-border-default)', margin: 0 }} />
                      </div>
                      <Button
                        type="button"
                        variant="secondary"
                        touchTarget
                        disabled
                        aria-disabled="true"
                        style={{ width: '100%' }}
                      >
                        Sign in with SSO
                      </Button>
                    </>
                  )}
                </div>
              </form>

              <FooterNotice />
            </AuthCard>
          </div>
        </div>
      </div>
    </div>
  )
}
