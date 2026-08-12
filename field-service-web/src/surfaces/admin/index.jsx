/**
 * @fileoverview Admin surface — reference data management console.
 *
 * SECURITY: Role-filtering here is a USABILITY affordance only. Every action
 * is independently denied server-side (403). Never rely on client-side role
 * checks as a security boundary.
 *
 * Privacy sub-routes (/admin/privacy/*) are guarded at the page level by
 * PermissionDeniedState for roles other than PRIVACY_ADMIN and ADMIN.
 */
import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { LoadingState } from '../../components/index.js'

const AuditSearchPage           = lazy(() => import('../../features/admin/audit/AuditSearchPage.jsx'))
const CustomersPage             = lazy(() => import('../../features/admin/customers/CustomersPage.jsx'))
const SitesPage                 = lazy(() => import('../../features/admin/sites/SitesPage.jsx'))
const AssetsPage                = lazy(() => import('../../features/admin/assets/AssetsPage.jsx'))
const TechniciansPage           = lazy(() => import('../../features/admin/technicians/TechniciansPage.jsx'))
const SkillsPage                = lazy(() => import('../../features/admin/skills/SkillsPage.jsx'))
const CertificationTypesPage    = lazy(() => import('../../features/admin/certifications/CertificationTypesPage.jsx'))
const TechnicianCertificationsPage = lazy(() => import('../../features/admin/certifications/TechnicianCertificationsPage.jsx'))
// Readiness report — MANAGER and ADMIN only (Phase 1 exit gate)
const ReadinessReportPage        = lazy(() => import('../../features/admin/readiness/ReadinessReportPage.jsx'))
// Privacy administration — PRIVACY_ADMIN and ADMIN only
const ClassificationRegistryPage = lazy(() => import('../../features/admin/privacy/ClassificationRegistryPage.jsx'))
const RetentionSchedulePage      = lazy(() => import('../../features/admin/privacy/RetentionSchedulePage.jsx'))
const DsarQueuePage              = lazy(() => import('../../features/admin/privacy/DsarQueuePage.jsx'))
const DsarRequestDetailPage      = lazy(() => import('../../features/admin/privacy/DsarRequestDetailPage.jsx'))

function S({ children }) {
  return <Suspense fallback={<LoadingState />}>{children}</Suspense>
}

export default function AdminSurface() {
  return (
    <Routes>
      <Route index element={<Navigate to="customers" replace />} />
      <Route path="customers" element={<S><CustomersPage /></S>} />
      <Route path="sites" element={<S><SitesPage /></S>} />
      <Route path="assets" element={<S><AssetsPage /></S>} />
      <Route path="technicians" element={<S><TechniciansPage /></S>} />
      <Route path="technicians/:id/certifications" element={<S><TechnicianCertificationsPage /></S>} />
      <Route path="skills" element={<S><SkillsPage /></S>} />
      <Route path="certification-types" element={<S><CertificationTypesPage /></S>} />
      {/* Readiness report route */}
      <Route path="readiness" element={<S><ReadinessReportPage /></S>} />
      {/* Privacy administration routes */}
      <Route path="privacy" element={<Navigate to="classifications" replace />} />
      <Route path="privacy/classifications" element={<S><ClassificationRegistryPage /></S>} />
      <Route path="privacy/retention" element={<S><RetentionSchedulePage /></S>} />
      <Route path="privacy/dsar" element={<S><DsarQueuePage /></S>} />
      <Route path="privacy/dsar/:id" element={<S><DsarRequestDetailPage /></S>} />
      {/* Audit trail — ADMIN and COMPLIANCE_REVIEWER only */}
      <Route path="audit" element={<S><AuditSearchPage /></S>} />
      <Route path="*" element={<Navigate to="customers" replace />} />
    </Routes>
  )
}
