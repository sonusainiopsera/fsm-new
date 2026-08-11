import { createContext, useContext, useState, useCallback } from 'react';

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
 * }} AuthContextValue
 */

/** @type {import('react').Context<AuthContextValue>} */
export const AuthContext = createContext({
  token: null,
  setToken: () => {},
  clearToken: () => {},
  isAuthenticated: false,
});

/**
 * Holds the decoded access token in memory only — never persisted to localStorage
 * or any other storage (token lives in JS memory per the security model).
 *
 * WO-090 will upgrade this provider with the full TanStack Query auth layer
 * and token refresh flow. This provider is the thin structural anchor.
 *
 * @param {{ children: import('react').ReactNode }} props
 */
export function AuthProvider({ children }) {
  const [token, setTokenState] = useState(/** @type {DecodedToken | null} */(null));

  const setToken = useCallback((t) => setTokenState(t), []);
  const clearToken = useCallback(() => setTokenState(null), []);

  return (
    <AuthContext.Provider value={{
      token,
      setToken,
      clearToken,
      isAuthenticated: token !== null,
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
