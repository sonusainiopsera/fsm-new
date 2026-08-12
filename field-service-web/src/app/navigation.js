/**
 * @fileoverview Declarative navigation manifest with role-based filtering.
 *
 * SECURITY NOTE (A01, BR-19):
 * Role-based navigation filtering is a USABILITY affordance only. It hides
 * navigation controls that a user cannot exercise, not as a security boundary.
 * ALL routes are protected by server-side authorisation. A server 403 response
 * is mapped to PermissionDeniedState by the router error mapper. The UI must
 * never grant access on the basis of the roles claim alone.
 */

export const SURFACES = {
  DISPATCH: 'dispatch',
  FIELD: 'field',
  OPERATIONS: 'operations',
  PORTAL: 'portal',
  ADMIN: 'admin',
}

/**
 * @typedef {{
 *   key: string,
 *   path: string,
 *   label: string,
 *   icon: string,
 *   allowedRoles: string[],
 *   surface: string,
 * }} NavItem
 */

/** @type {NavItem[]} */
export const NAV_MANIFEST = [
  {
    key: 'dispatch-board',
    path: '/dispatch',
    label: 'Dispatch Board',
    icon: 'grid',
    allowedRoles: ['DISPATCHER', 'ADMIN'],
    surface: SURFACES.DISPATCH,
  },
  {
    key: 'work-orders',
    path: '/dispatch/work-orders',
    label: 'Work Orders',
    icon: 'clipboard',
    allowedRoles: ['DISPATCHER', 'MANAGER', 'ADMIN'],
    surface: SURFACES.DISPATCH,
  },
  {
    key: 'technicians',
    path: '/dispatch/technicians',
    label: 'Technicians',
    icon: 'users',
    allowedRoles: ['DISPATCHER', 'MANAGER', 'ADMIN'],
    surface: SURFACES.DISPATCH,
  },
  {
    key: 'inventory',
    path: '/dispatch/inventory',
    label: 'Inventory',
    icon: 'box',
    allowedRoles: ['DISPATCHER', 'MANAGER', 'ADMIN'],
    surface: SURFACES.DISPATCH,
  },
  {
    key: 'my-jobs',
    path: '/field/jobs',
    label: 'My Jobs',
    icon: 'briefcase',
    allowedRoles: ['TECHNICIAN'],
    surface: SURFACES.FIELD,
  },
  {
    key: 'ops-dashboard',
    path: '/operations',
    label: 'Dashboard',
    icon: 'bar-chart',
    allowedRoles: ['MANAGER', 'ADMIN'],
    surface: SURFACES.OPERATIONS,
  },
  {
    key: 'audit-log',
    path: '/operations/audit',
    label: 'Audit Log',
    icon: 'file-text',
    allowedRoles: ['MANAGER', 'ADMIN'],
    surface: SURFACES.OPERATIONS,
  },
  {
    key: 'my-requests',
    path: '/portal/requests',
    label: 'My Requests',
    icon: 'help-circle',
    allowedRoles: ['CUSTOMER'],
    surface: SURFACES.PORTAL,
  },
  {
    key: 'admin-customers',
    path: '/admin/customers',
    label: 'Customers',
    icon: 'users',
    allowedRoles: ['ADMIN', 'MANAGER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'admin-sites',
    path: '/admin/sites',
    label: 'Sites',
    icon: 'map-pin',
    allowedRoles: ['ADMIN', 'MANAGER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'admin-assets',
    path: '/admin/assets',
    label: 'Assets',
    icon: 'cpu',
    allowedRoles: ['ADMIN', 'MANAGER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'admin-technicians',
    path: '/admin/technicians',
    label: 'Technicians',
    icon: 'tool',
    allowedRoles: ['ADMIN', 'MANAGER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'admin-skills',
    path: '/admin/skills',
    label: 'Skills',
    icon: 'award',
    allowedRoles: ['ADMIN', 'MANAGER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'admin-cert-types',
    path: '/admin/certification-types',
    label: 'Certification Types',
    icon: 'shield',
    allowedRoles: ['ADMIN', 'MANAGER', 'DISPATCHER'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'settings',
    path: '/settings',
    label: 'Settings',
    icon: 'settings',
    allowedRoles: ['ADMIN'],
    surface: SURFACES.DISPATCH,
  },
  // Readiness report — Phase 1 exit gate (MANAGER and ADMIN only)
  {
    key: 'readiness-report',
    path: '/admin/readiness',
    label: 'Data Readiness',
    icon: 'check-circle',
    allowedRoles: ['MANAGER', 'ADMIN'],
    surface: SURFACES.ADMIN,
  },
  // Privacy administration — PRIVACY_ADMIN and ADMIN only
  {
    key: 'privacy-classifications',
    path: '/admin/privacy/classifications',
    label: 'Classification Registry',
    icon: 'tag',
    allowedRoles: ['PRIVACY_ADMIN', 'ADMIN'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'privacy-retention',
    path: '/admin/privacy/retention',
    label: 'Retention Schedule',
    icon: 'clock',
    allowedRoles: ['PRIVACY_ADMIN', 'ADMIN'],
    surface: SURFACES.ADMIN,
  },
  {
    key: 'privacy-dsar',
    path: '/admin/privacy/dsar',
    label: 'DSAR Queue',
    icon: 'inbox',
    allowedRoles: ['PRIVACY_ADMIN', 'ADMIN'],
    surface: SURFACES.ADMIN,
  },
]

/**
 * Filters the navigation manifest to items visible for the given roles.
 *
 * USABILITY ONLY — the server 403 is the sole security control. Never use
 * this function to decide whether to fetch data or whether to allow writes.
 *
 * @param {string[]} roles - roles from the decoded JWT claims
 * @returns {NavItem[]}
 */
export function filterNavForRoles(roles) {
  if (!Array.isArray(roles) || roles.length === 0) {
    // Empty or unknown roles produce no navigation — structured warning logged so
    // operators can detect misconfigured tokens without the shell crashing.
    if (typeof console !== 'undefined' && roles.length === 0) {
      console.warn('[navigation] Empty roles claim — rendering no navigation items.')
    }
    return []
  }

  const roleSet = new Set(roles)

  // Warn (but do not throw) for unrecognised role values so future roles don't
  // crash the shell while the frontend lags a deploy.
  const KNOWN_ROLES = new Set(['DISPATCHER', 'TECHNICIAN', 'MANAGER', 'CUSTOMER', 'ADMIN', 'PRIVACY_ADMIN'])
  roles.forEach(r => {
    if (!KNOWN_ROLES.has(r) && typeof console !== 'undefined') {
      console.warn(`[navigation] Unrecognised role "${r}" — skipping.`)
    }
  })

  return NAV_MANIFEST.filter(item =>
    item.allowedRoles.some(r => roleSet.has(r))
  )
}
