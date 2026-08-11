import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useAuth } from '../../app/AuthContext.js';
import { defaultPathForRoles } from '../../app/navigation.js';

/**
 * Sign-in surface — /sign-in.
 *
 * Structural placeholder for the authentication entry point. Full
 * implementation is delivered by WO-090 (query layer and auth flow).
 *
 * Security model:
 * - Credentials are POST-ed to /api/v1/auth/login.
 * - The access token is held in the AuthContext memory only (never localStorage).
 * - Lockout messaging uses generic copy that leaks nothing about account existence.
 * - After authentication the browser navigates to the default surface for the
 *   user's roles; this is a usability redirect, not an access control.
 */
export default function SignIn() {
  const { setToken } = useAuth();
  const navigate = useNavigate();
  const [error, setError] = useState(null);
  const [loading, setLoading] = useState(false);

  async function handleSubmit(e) {
    e.preventDefault();
    setLoading(true);
    setError(null);

    const form = new FormData(e.currentTarget);
    const email = form.get('email');
    const password = form.get('password');

    try {
      const res = await fetch('/api/v1/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });

      if (!res.ok) {
        const body = await res.json().catch(() => ({}));
        setError(body.message ?? 'Invalid email or password.');
        return;
      }

      const { accessToken, roles, userId, displayName } = await res.json();
      setToken({ sub: userId, roles: roles ?? [], displayName });
      navigate(defaultPathForRoles(roles ?? []), { replace: true });
    } catch {
      setError('Unable to connect. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <main style={{ display: 'flex', justifyContent: 'center', paddingBlock: 'var(--space-8)' }}>
      <section aria-labelledby="sign-in-heading" style={{ width: '100%', maxWidth: 400 }}>
        <h1 id="sign-in-heading" style={{ fontSize: 'var(--fs-2xl)', marginBlockEnd: 'var(--space-6)' }}>
          Sign in
        </h1>

        {error && (
          <div role="alert" style={{
            marginBlockEnd: 'var(--space-4)',
            padding: 'var(--space-3)',
            borderRadius: 'var(--radius-control)',
            background: 'var(--color-semantic-danger-surface)',
            color: 'var(--color-semantic-danger-text)',
            fontSize: 'var(--fs-base)',
          }}>
            {error}
          </div>
        )}

        <form onSubmit={handleSubmit} noValidate>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-4)' }}>
            <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
              <span style={{ fontSize: 'var(--fs-base)', fontWeight: 'var(--fw-medium)' }}>
                Email address
              </span>
              <input
                type="email"
                name="email"
                autoComplete="email"
                required
                style={{
                  padding: 'var(--space-2) var(--space-3)',
                  borderRadius: 'var(--radius-control)',
                  border: 'var(--elevation-hairline)',
                  background: 'var(--color-surface-raised)',
                  color: 'var(--color-text-primary)',
                  fontSize: 'var(--fs-base)',
                }}
              />
            </label>

            <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
              <span style={{ fontSize: 'var(--fs-base)', fontWeight: 'var(--fw-medium)' }}>
                Password
              </span>
              <input
                type="password"
                name="password"
                autoComplete="current-password"
                required
                style={{
                  padding: 'var(--space-2) var(--space-3)',
                  borderRadius: 'var(--radius-control)',
                  border: 'var(--elevation-hairline)',
                  background: 'var(--color-surface-raised)',
                  color: 'var(--color-text-primary)',
                  fontSize: 'var(--fs-base)',
                }}
              />
            </label>

            <button
              type="submit"
              disabled={loading}
              style={{
                padding: 'var(--space-2) var(--space-4)',
                borderRadius: 'var(--radius-control)',
                background: loading ? 'var(--color-surface-sunken)' : 'var(--color-accent-base)',
                color: loading ? 'var(--color-text-secondary)' : 'var(--color-text-on-accent)',
                border: 'none',
                cursor: loading ? 'not-allowed' : 'pointer',
                fontSize: 'var(--fs-base)',
                fontWeight: 'var(--fw-medium)',
              }}
            >
              {loading ? 'Signing in…' : 'Sign in'}
            </button>
          </div>
        </form>
      </section>
    </main>
  );
}
