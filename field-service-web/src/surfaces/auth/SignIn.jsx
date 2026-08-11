import React, { useId, useState } from 'react';
import { Link } from 'react-router-dom';

import { useAppearance } from '../../appearance/AppearanceContext.js';
import { useSignIn } from './useSignIn.js';

import styles from './SignIn.module.css';

/** SSO held behind flag — customer identity model not yet ratified (WO-116). */
const SSO_ENABLED = false;

/**
 * Sign-in page — single authentication entry point for all four personas.
 *
 * Component hierarchy:
 *   AppearanceToggle
 *   SplitLayout
 *     BrandPanel (decorative — aria-hidden)
 *     AuthCard (main landmark)
 *       <h1> "Sign in"
 *       GenericErrorAlert (aria-live="polite", role="alert")
 *       <form>
 *         EmailInput
 *         PasswordInput + ShowPasswordToggle
 *         RememberDeviceSwitch
 *         ForgotPasswordLink
 *         SubmitButton (loading state)
 *       [SSO_ENABLED] SsoDivider
 *       [SSO_ENABLED] SsoButton
 *       FooterNotice
 *
 * Security model:
 * - Credentials are submitted to POST /api/v1/auth/login.
 * - The returned access token is held exclusively in the module-scoped
 *   tokenStore — never in localStorage, sessionStorage, or window globals.
 * - The refresh handle is set only as an HttpOnly cookie by the server.
 * - Error copy is rendered verbatim from the API response and never inferred,
 *   so no account-existence information is disclosed (BR-12).
 */
export default function SignIn() {
  const { appearance, toggleAppearance } = useAppearance();
  const { signIn, isPending, error, reset } = useSignIn();

  const [showPassword, setShowPassword] = useState(false);
  const [rememberDevice, setRememberDevice] = useState(false);

  const emailId = useId();
  const passwordId = useId();
  const errorId = useId();
  const rememberDeviceId = useId();

  const errorMessage = error?.message ?? null;

  /**
   * @param {React.FormEvent<HTMLFormElement>} e
   */
  function handleSubmit(e) {
    e.preventDefault();
    if (isPending) return;

    const form = new FormData(e.currentTarget);
    const email = String(form.get('email') ?? '').trim();
    const password = String(form.get('password') ?? '');
    signIn(email, password);
  }

  return (
    <div className={styles.page}>
      <button
        type="button"
        className={styles.appearanceToggle}
        onClick={toggleAppearance}
        aria-label={`Switch to ${appearance === 'light' ? 'dark' : 'light'} appearance`}
      >
        {appearance === 'light' ? '☾' : '☀'}
      </button>

      <div className={styles.layout}>
        {/* Brand panel — decorative, hidden from assistive technology */}
        <div className={styles.brandPanel} aria-hidden="true">
          <div className={styles.brandContent}>
            <span className={styles.brandName}>Field Service Platform</span>
            <p className={styles.brandTagline}>Manage your field operations</p>
          </div>
        </div>

        {/* Auth card */}
        <main className={styles.authCard} aria-label="Sign-in form">
          <h1 className={styles.heading}>Sign in</h1>

          {/* Generic error alert — renders only API-supplied copy (BR-12) */}
          {errorMessage !== null && (
            <div
              id={errorId}
              role="alert"
              aria-live="polite"
              className={styles.errorAlert}
            >
              {errorMessage}
            </div>
          )}

          <form
            className={styles.form}
            onSubmit={handleSubmit}
            noValidate
            aria-label="Sign-in credentials"
          >
            {/* Email input */}
            <div className={styles.field}>
              <label htmlFor={emailId} className={styles.label}>
                Email address
              </label>
              <input
                id={emailId}
                name="email"
                type="email"
                className={styles.input}
                autoComplete="email"
                required
                aria-required="true"
                aria-describedby={errorMessage !== null ? errorId : undefined}
                aria-invalid={errorMessage !== null ? 'true' : undefined}
                onChange={() => { if (error) reset(); }}
              />
            </div>

            {/* Password input with show/hide toggle */}
            <div className={styles.field}>
              <label htmlFor={passwordId} className={styles.label}>
                Password
              </label>
              <div className={styles.passwordWrapper}>
                <input
                  id={passwordId}
                  name="password"
                  type={showPassword ? 'text' : 'password'}
                  className={`${styles.input} ${styles.passwordInput}`}
                  autoComplete="current-password"
                  required
                  aria-required="true"
                  aria-describedby={errorMessage !== null ? errorId : undefined}
                  aria-invalid={errorMessage !== null ? 'true' : undefined}
                  onChange={() => { if (error) reset(); }}
                />
                <button
                  type="button"
                  className={styles.showPasswordButton}
                  onClick={() => setShowPassword((v) => !v)}
                  aria-label={showPassword ? 'Hide password' : 'Show password'}
                  aria-pressed={showPassword}
                  aria-controls={passwordId}
                >
                  {showPassword ? 'Hide' : 'Show'}
                </button>
              </div>
            </div>

            {/* Remember-device switch */}
            <div className={styles.rememberRow}>
              <input
                id={rememberDeviceId}
                type="checkbox"
                className={styles.checkbox}
                checked={rememberDevice}
                onChange={(e) => setRememberDevice(e.target.checked)}
              />
              <label htmlFor={rememberDeviceId} className={styles.checkboxLabel}>
                Remember this device
              </label>
            </div>

            {/* Forgot-password link — routed to placeholder until flow is ratified */}
            <div className={styles.forgotRow}>
              <Link to="/forgot-password" className={styles.link}>
                Forgot password?
              </Link>
            </div>

            {/* Submit button — disables and shows loading state while in flight */}
            <button
              type="submit"
              className={styles.submitButton}
              disabled={isPending}
              aria-busy={isPending ? 'true' : undefined}
              aria-disabled={isPending}
            >
              {isPending && <span className={styles.spinner} aria-hidden="true" />}
              {isPending ? 'Signing in…' : 'Sign in'}
            </button>
          </form>

          {/* SSO — behind feature flag until customer identity model is ratified */}
          {SSO_ENABLED && (
            <>
              <div className={styles.ssoDivider} aria-hidden="true">
                <span>or continue with</span>
              </div>
              <button type="button" className={styles.ssoButton}>
                Sign in with SSO
              </button>
            </>
          )}

          <p className={styles.footerNotice}>
            By signing in you agree to the Field Service Platform terms of use.
          </p>
        </main>
      </div>
    </div>
  );
}
