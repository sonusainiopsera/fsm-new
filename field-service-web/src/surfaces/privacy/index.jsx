/**
 * Privacy administration surface — route group for /privacy/*.
 *
 * Lazy-loaded entry chunk for the privacy administration section.
 * Accessible to PRIVACY_ADMIN and ADMIN roles only.
 *
 * Routes:
 *   /privacy                         → redirect to /privacy/classifications
 *   /privacy/classifications         → ClassificationRegistryPage
 *   /privacy/retention               → RetentionSchedulePage
 *   /privacy/dsar                    → DsarQueuePage
 *   /privacy/dsar/:id                → DsarRequestDetailPage
 *
 * SECURITY NOTE (A01, BR-19):
 * Navigation links are hidden for non-PRIVACY_ADMIN/ADMIN roles (usability only).
 * Server-side authorisation is the real control — a 403 from any endpoint
 * renders PermissionDeniedState via the GlobalExceptionHandler.
 */

import React from 'react';
import { Routes, Route, NavLink, Navigate, useNavigate, useParams } from 'react-router-dom';

import { PermissionDeniedState } from '../../components/index.js';
import { ClassificationRegistryPage } from '../../features/privacy/ClassificationRegistryPage.jsx';
import { RetentionSchedulePage }      from '../../features/privacy/RetentionSchedulePage.jsx';
import { DsarQueuePage }              from '../../features/privacy/DsarQueuePage.jsx';
import { DsarRequestDetailPage }      from '../../features/privacy/DsarRequestDetailPage.jsx';

import styles from '../admin/AdminSurface.module.css';

const PRIVACY_ROLES = new Set(['PRIVACY_ADMIN', 'ADMIN']);

const SUB_NAV = [
  { to: '/privacy/classifications', label: 'Classifications' },
  { to: '/privacy/retention',       label: 'Retention schedule' },
  { to: '/privacy/dsar',            label: 'DSAR queue' },
];

/**
 * Thin wrapper to pass dsarId from the URL param to DsarRequestDetailPage.
 * @param {{ roles?: string[] }} props
 */
function DsarDetailRoute({ roles }) {
  const { id } = useParams();
  const navigate = useNavigate();
  return (
    <DsarRequestDetailPage
      dsarId={id}
      roles={roles}
      onBack={() => navigate('/privacy/dsar')}
    />
  );
}

/**
 * @param {{ roles?: string[] }} props
 */
function DsarQueueRoute({ roles }) {
  const navigate = useNavigate();
  return (
    <DsarQueuePage
      roles={roles}
      onSelect={(row) => navigate(`/privacy/dsar/${row.id}`)}
    />
  );
}

export default function PrivacySurface({ roles = [] }) {
  const hasAccess = roles.some((r) => PRIVACY_ROLES.has(r));

  if (!hasAccess) {
    return <PermissionDeniedState description="This area requires the Privacy Admin or Admin role." />;
  }

  return (
    <div className={styles.surface}>
      <nav className={styles.subnav} aria-label="Privacy administration navigation">
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
          <Route index element={<Navigate to="classifications" replace />} />
          <Route path="classifications" element={<ClassificationRegistryPage roles={roles} />} />
          <Route path="retention"       element={<RetentionSchedulePage roles={roles} />} />
          <Route path="dsar"            element={<DsarQueueRoute roles={roles} />} />
          <Route path="dsar/:id"        element={<DsarDetailRoute roles={roles} />} />
        </Routes>
      </div>
    </div>
  );
}
