import { createContext, useCallback, useContext, useEffect, useState } from 'react';

import { tokenStore } from '../api/tokenStore.js';

/**
 * @typedef {{
 *   sub: string,
 *   roles: string[],
 *   displayName?: string,
 *   exp?: number,
 * }} DecodedToken
 *
 * @typedef {{
 *   token: DecodedToken | null,
 *   setToken: (t: DecodedToken | null) => void,
 *   clearToken: () => void,
 *   isAuthenticated: boolean,
 *   isBootComplete: boolean,
 * }} AuthContextValue
 */

/** @type {import('react').Context<AuthContextValue>} */
export const AuthContext = createContext({
  token: null,
  setToken: () => {},
  clearToken: () => {},
  isAuthenticated: false,
  isBootComplete: false,
});

/**
 * Decodes the payload segment of a JWT.
 * Returns null for any non-decodable input so callers never throw.
 *
 * @param {string} token
 * @returns {Record<string, unknown> | null}
 */
function decodeJwtPayload(token) {
  const parts = token.split('.');
  if (parts.length < 2 || !parts[1]) return null;
  try {
    const padded = parts[1].replace(/-/g, '+').replace(/_/g, '/');
    return /** @type {Record<string, unknown>} */ (JSON.parse(atob(padded)));
  } catch {
    return null;
  }
}

/**
 * Calls POST /api/v1/auth/refresh with credentials so the HttpOnly refresh
 * cookie is included.  Returns the new raw access token on success.
 * Throws on any non-200 response.
 *
 * @returns {Promise<string>}
 */
async function _doBootRefresh() {
  const res = await fetch('/api/v1/auth/refresh', {
    method: 'POST',
    credentials: 'include',
    headers: { Accept: 'application/json' },
  });
  if (!res.ok) throw new Error('Boot refresh rejected');
  const data = await res.json();
  if (typeof data?.accessToken !== 'string' || data.accessToken.length === 0) {
    throw new Error('Boot refresh: missing accessToken');
  }
  return data.accessToken;
}

/**
 * Holds the decoded access token in memory only — never persisted to
 * localStorage or any other storage.
 *
 * On mount, a silent refresh is attempted from the HttpOnly cookie.  If it
 * succeeds, the decoded claims are stored and `isAuthenticated` becomes true
 * without forcing the user to the sign-in page. If it fails, the user remains
 * unauthenticated and is redirected by the AppShell once `isBootComplete`
 * becomes true.
 *
 * During normal use, the module subscribes to `tokenStore` changes so that
 * a failed mid-session refresh (which clears tokenStore) is immediately
 * reflected in `isAuthenticated`.
 *
 * @param {{ children: import('react').ReactNode }} props
 */
export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(/** @type {DecodedToken | null} */(null));
  const [isBootComplete, setIsBootComplete] = useState(false);

  const setToken = useCallback((/** @type {DecodedToken | null} */ t) => setTokenState(t), []);
  const clearToken = useCallback(() => {
    tokenStore.clear();
    setTokenState(null);
  }, []);

  useEffect(() => {
    let cancelled = false;

    async function boot() {
      try {
        const rawToken = await tokenStore.refresh(_doBootRefresh);
        if (cancelled) return;
        const claims = decodeJwtPayload(rawToken);
        if (claims) {
          setTokenState({
            sub: typeof claims.sub === 'string' ? claims.sub : '',
            roles: Array.isArray(claims.roles)
              ? /** @type {string[]} */ (claims.roles).filter((r) => typeof r === 'string')
              : [],
            displayName: typeof claims.displayName === 'string' ? claims.displayName : undefined,
            exp: typeof claims.exp === 'number' ? claims.exp : undefined,
          });
        }
      } catch {
        // Silent failure — user must sign in explicitly; AppShell redirects
      } finally {
        if (!cancelled) setIsBootComplete(true);
      }
    }

    boot();

    // Sync AuthContext token state whenever tokenStore is cleared (e.g., after
    // a failed mid-session refresh handled by the http client).
    const unsub = tokenStore.subscribe((newRaw) => {
      if (newRaw === null) {
        setTokenState(null);
      }
    });

    return () => {
      cancelled = true;
      unsub();
    };
  }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <AuthContext.Provider value={{
      token,
      setToken,
      clearToken,
      isAuthenticated: token !== null,
      isBootComplete,
    }}>
      {children}
    </AuthContext.Provider>
  );
}

/**
 * @returns {AuthContextValue}
 */
export function useAuth() {
  return useContext(AuthContext);
}
