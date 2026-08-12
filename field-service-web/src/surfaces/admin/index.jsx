/**
 * Admin surface — route group for /admin/*.
 *
 * Lazy-loaded entry chunk for the administration section.
 * Accessible to ADMIN and MANAGER (DISPATCHER read-only on some screens).
 *
 * Routes:
 *   /admin                 → redirect to /admin/customers
 *   /admin/customers       → CustomersPage
 *   /admin/sites           → SitesPage
 *   /admin/assets          → AssetsPage
 *   /admin/technicians     → TechniciansPage
 *   /admin/certification-types → CertificationTypesPage
 *   /admin/certifications  → TechnicianCertificationsPage
 *
 * SECURITY NOTE (A01, BR-19):
 * Navigation links are hidden for non-ADMIN/MANAGER roles (usability only).
 * Server-side authorization is the real control — a 403 from any endpoint
 * renders PermissionDeniedState via the GlobalExceptionHandler.
 */

import React from 'react';
import { Routes, Route, NavLink, Navigate } from 'react-router-dom';

import { CustomersPage }             from '../../features/admin/CustomersPage.jsx';
import { SitesPage }                 from '../../features/admin/SitesPage.jsx';
import { AssetsPage }                from '../../features/admin/AssetsPage.jsx';
import { TechniciansPage }           from '../../features/admin/TechniciansPage.jsx';
import { CertificationTypesPage }    from '../../features/admin/CertificationTypesPage.jsx';
import { TechnicianCertificationsPage } from '../../features/admin/TechnicianCertificationsPage.jsx';
import { ReadinessReportPage }           from '../../features/admin/ReadinessReportPage.jsx';

import styles from './AdminSurface.module.css';

const SUB_NAV = [
  { to: '/admin/customers',            label: 'Customers' },
  { to: '/admin/sites',                label: 'Sites' },
  { to: '/admin/assets',               label: 'Assets' },
  { to: '/admin/technicians',          label: 'Technicians' },
  { to: '/admin/certification-types',  label: 'Cert Types' },
  { to: '/admin/certifications',       label: 'Certifications' },
  { to: '/admin/readiness-report',     label: 'Readiness' },
];

/**
 * @param {{ roles?: string[] }} props
 */
export default function AdminSurface({ roles = [] }) {
  return (
    <div className={styles.surface}>
      <nav className={styles.subnav} aria-label="Administration navigation">
        {SUB_NAV.map(({ to, label }) => (
          <NavLink
            key={to}
            to={to}
            className={({ isActive }) =>
              [styles.navLink, isActive ? styles.navLinkActive : ''].filter(Boolean).join(' ')
            }
          >
            {label}
          </NavLink>
        ))}
      </nav>

      <div className={styles.content}>
        <Routes>
          <Route index element={<Navigate to="customers" replace />} />
          <Route path="customers"           element={<CustomersPage roles={roles} />} />
          <Route path="sites"               element={<SitesPage roles={roles} />} />
          <Route path="assets"              element={<AssetsPage roles={roles} />} />
          <Route path="technicians"         element={<TechniciansPage roles={roles} />} />
          <Route path="certification-types" element={<CertificationTypesPage roles={roles} />} />
          <Route path="certifications"      element={<TechnicianCertificationsPage roles={roles} />} />
          <Route path="readiness-report"    element={<ReadinessReportPage roles={roles} />} />
        </Routes>
      </div>
    </div>
  );
}
