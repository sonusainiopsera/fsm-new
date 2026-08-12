/**
 * Declarative route manifest for the application shell.
 *
 * IMPORTANT — SECURITY NOTICE:
 * Role filtering here is a USABILITY affordance only. It controls which
 * navigation items are visible to reduce cognitive noise for each persona.
 * It is NOT a security control (A01, BR-19). Every route must be independently
 * protected by server-side authorisation. A hand-crafted URL to an unauthorised
 * route resolves to a server 403, never to a client-side bypass.
 *
 * See docs/APP_SHELL.md for the full authority model.
 */

/**
 * @typedef {'dispatch' | 'field' | 'operations' | 'portal'} Surface
 *
 * @typedef {{
 *   path: string,
 *   label: string,
 *   icon: string,
 *   allowedRoles: string[],
 *   surface: Surface,
 * }} NavItem
 */

/** Canonical role constants — mirror the server-side AppRole enum. */
export const ROLES = /** @type {const} */ ({
  ADMIN: 'ADMIN',
  DISPATCHER: 'DISPATCHER',
  TECHNICIAN: 'TECHNICIAN',
  MANAGER: 'MANAGER',
  CUSTOMER: 'CUSTOMER',
});

/**
 * All navigation entries in display order.
 * `allowedRoles` is the set of roles for which this item is surfaced —
 * an empty array means universally visible (e.g. sign-in).
 */
export const NAV_ITEMS = /** @type {NavItem[]} */ ([
  {
    path: '/admin',
    label: 'Administration',
    icon: 'settings',
    allowedRoles: [ROLES.ADMIN, ROLES.MANAGER],
    surface: 'admin',
  },
  {
    path: '/dispatch',
    label: 'Dispatch Board',
    icon: 'grid',
    allowedRoles: [ROLES.DISPATCHER, ROLES.ADMIN, ROLES.MANAGER],
    surface: 'dispatch',
  },
  {
    path: '/field',
    label: 'My Jobs',
    icon: 'briefcase',
    allowedRoles: [ROLES.TECHNICIAN],
    surface: 'field',
  },
  {
    path: '/operations',
    label: 'Operations',
    icon: 'bar-chart',
    allowedRoles: [ROLES.MANAGER, ROLES.ADMIN],
    surface: 'operations',
  },
  {
    path: '/portal',
    label: 'My Requests',
    icon: 'inbox',
    allowedRoles: [ROLES.CUSTOMER],
    surface: 'portal',
  },
]);

/**
 * Returns the nav items visible for the given role set.
 *
 * A token carrying multiple roles produces the union of entries without
 * duplicates. An unknown role value yields only universally permitted items
 * and emits a structured console warning rather than throwing.
 *
 * @param {string[]} roles - Roles claim from the decoded access token
 * @returns {NavItem[]}
 */
export function filterNavItems(roles) {
  const knownRoles = new Set(Object.values(ROLES));

  for (const role of roles) {
    if (!knownRoles.has(role)) {
      console.warn(`[navigation] Unrecognised role "${role}" — rendering universal nav only`);
    }
  }

  const roleSet = new Set(roles);
  const seen = new Set();
  const result = [];

  for (const item of NAV_ITEMS) {
    if (seen.has(item.path)) continue;
    if (item.allowedRoles.some((r) => roleSet.has(r))) {
      seen.add(item.path);
      result.push(item);
    }
  }

  return result;
}

/**
 * Returns the default surface path for a given role set.
 * Used to redirect the root path (/) after sign-in.
 *
 * @param {string[]} roles
 * @returns {string}
 */
export function defaultPathForRoles(roles) {
  const visible = filterNavItems(roles);
  return visible.length > 0 ? visible[0].path : '/sign-in';
}
