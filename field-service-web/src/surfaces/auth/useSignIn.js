import { useMutation } from '@tanstack/react-query';
import { useNavigate } from 'react-router-dom';

import { useAuth } from '../../app/AuthContext.js';
import { post } from '../../api/http.js';
import { tokenStore } from '../../api/tokenStore.js';
import { defaultPathForRoles } from '../../app/navigation.js';

/**
 * Validates and narrows the login API response at the boundary before any
 * value reaches the token store.
 *
 * @param {unknown} data
 * @returns {{ accessToken: string, expiresIn: number, user: { id: string, displayName: string, roles: string[] } }}
 */
function validateLoginResponse(data) {
  if (data === null || typeof data !== 'object' || Array.isArray(data)) {
    throw new Error('Login response failed boundary validation: not an object');
  }
  const d = /** @type {Record<string, unknown>} */ (data);
  if (typeof d.accessToken !== 'string' || d.accessToken.length === 0) {
    throw new Error('Login response failed boundary validation: missing accessToken');
  }
  if (typeof d.expiresIn !== 'number') {
    throw new Error('Login response failed boundary validation: missing expiresIn');
  }
  const userRaw = d.user && typeof d.user === 'object' && !Array.isArray(d.user)
    ? /** @type {Record<string, unknown>} */ (d.user)
    : null;
  return {
    accessToken: d.accessToken,
    expiresIn: d.expiresIn,
    user: {
      id: typeof userRaw?.id === 'string' ? userRaw.id : '',
      displayName: typeof userRaw?.displayName === 'string' ? userRaw.displayName : '',
      roles: Array.isArray(userRaw?.roles)
        ? /** @type {string[]} */ (userRaw.roles).filter((r) => typeof r === 'string')
        : [],
    },
  };
}

/**
 * @typedef {{
 *   signIn: (email: string, password: string) => void,
 *   isPending: boolean,
 *   error: import('../../api/errors.js').ClientError | null,
 *   reset: () => void,
 * }} UseSignInReturn
 */

/**
 * TanStack Query mutation for the login flow.
 *
 * On success: stores the raw access token in the module-scoped tokenStore
 * (never in localStorage/sessionStorage), updates AuthContext with decoded
 * claims, and navigates to the role-appropriate landing route.
 *
 * No retry on 401 (INVALID_CREDENTIALS) or 400 (VALIDATION_FAILED).
 * The mutation's error holds the full ClientError with API-supplied message.
 *
 * @returns {UseSignInReturn}
 */
export function useSignIn() {
  const { setToken } = useAuth();
  const navigate = useNavigate();

  const mutation = useMutation({
    mutationFn: /** @param {{ email: string, password: string }} creds */
    async (creds) => {
      const raw = await post('/auth/login', { email: creds.email, password: creds.password });
      return validateLoginResponse(raw);
    },
    onSuccess: (data) => {
      tokenStore.set(data.accessToken);
      setToken({
        sub: data.user.id,
        roles: data.user.roles,
        displayName: data.user.displayName,
      });
      navigate(defaultPathForRoles(data.user.roles), { replace: true });
    },
    retry: (/** @type {number} */ _count, /** @type {unknown} */ err) => {
      const e = /** @type {{ status?: number }} */ (err);
      return e?.status !== 401 && e?.status !== 400;
    },
  });

  return {
    signIn: (email, password) => mutation.mutate({ email, password }),
    isPending: mutation.isPending,
    error: /** @type {import('../../api/errors.js').ClientError | null} */ (mutation.error),
    reset: mutation.reset,
  };
}
