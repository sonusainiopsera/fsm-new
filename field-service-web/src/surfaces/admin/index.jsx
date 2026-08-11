/**
 * @fileoverview Admin surface — reference data management console.
 *
 * SECURITY: Role-filtering here is a USABILITY affordance only. Every action
 * is independently denied server-side (403). Never rely on client-side role
 * checks as a security boundary.
 */
import { lazy, Suspense } from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { LoadingState } from '../../components/index.js'

const CustomersPage             = lazy(() => import('../../features/admin/customers/CustomersPage.jsx'))
const SitesPage                 = lazy(() => import('../../features/admin/sites/SitesPage.jsx'))
const AssetsPage                = lazy(() => import('../../features/admin/assets/AssetsPage.jsx'))
const TechniciansPage           = lazy(() => import('../../features/admin/technicians/TechniciansPage.jsx'))
const SkillsPage                = lazy(() => import('../../features/admin/skills/SkillsPage.jsx'))
const CertificationTypesPage    = lazy(() => import('../../features/admin/certifications/CertificationTypesPage.jsx'))
const TechnicianCertificationsPage = lazy(() => import('../../features/admin/certifications/TechnicianCertificationsPage.jsx'))

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
      <Route path="*" element={<Navigate to="customers" replace />} />
    </Routes>
  )
}
