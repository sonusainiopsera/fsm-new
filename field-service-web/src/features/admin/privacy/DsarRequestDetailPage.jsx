/**
 * @fileoverview DSAR Request Detail — state history, identity verification,
 * export manifest, and erasure confirmation.
 *
 * AC-6: Export action requests a fresh short-lived URL per click, never cached.
 * AC-6: Export disabled (with explanation) when not identity-verified.
 * AC-7: Erasure flow via ErasureConfirmDialog — destructive confirmation.
 * Withdrawn/Rejected: export and erasure actions absent.
 *
 * @module features/admin/privacy/DsarRequestDetailPage
 */
import { useState, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { useAuth } from '../../../app/AuthContext.js'
import {
  PageHeader, Button,
  LoadingState, ErrorState, PermissionDeniedState,
} from '../../../components/index.js'
import { useDsarRequest, useDsarExportUrl, computeRemainingDays } from '../../privacy/hooks/useDsarRequests.js'
import { useErasureMutation } from '../../privacy/hooks/useSubjectRights.js'
import { ErasureConfirmDialog } from './ErasureConfirmDialog.jsx'

const TERMINAL_STATES = new Set(['FULFILLED', 'REJECTED', 'WITHDRAWN'])
const VERIFIED_OR_LATER = new Set(['VERIFIED', 'IN_PROGRESS', 'FULFILLED'])

export default function DsarRequestDetailPage() {
  const { id } = useParams()
  const { roles } = useAuth()
  const navigate = useNavigate()

  const canView = roles.some(r => ['PRIVACY_ADMIN', 'ADMIN'].includes(r))
  if (!canView) return <PermissionDeniedState />

  const { dsar, isLoading, isError, error, refetch } = useDsarRequest(id)

  if (isLoading) return <LoadingState />
  if (isError) return <ErrorState onRetry={refetch} message={error?.message} />
  if (!dsar) return <LoadingState />

  return <DsarDetailContent dsar={dsar} onBack={() => navigate('/admin/privacy/dsar')} />
}

/** @param {{ dsar: import('../../privacy/api/privacyClient.js').DsarDetail, onBack: () => void }} props */
function DsarDetailContent({ dsar, onBack }) {
  const [erasureOpen, setErasureOpen] = useState(false)
  const [exportError, setExportError] = useState(/** @type {string | null} */ (null))

  const exportUrlMutation = useDsarExportUrl()
  const erasureMutation = useErasureMutation()

  const isIdentityVerified = VERIFIED_OR_LATER.has(dsar.state)
  const isTerminal = TERMINAL_STATES.has(dsar.state)
  const remainingDays = computeRemainingDays(dsar.dueAt)

  const handleDownload = useCallback(() => {
    setExportError(null)
    exportUrlMutation.mutate(
      { id: dsar.id },
      {
        onSuccess: ({ downloadUrl }) => {
          // Open the fresh URL — never persisted in state or storage
          window.open(downloadUrl, '_blank', 'noreferrer')
        },
        onError: (err) => setExportError(err.message),
      }
    )
  }, [dsar.id, exportUrlMutation])

  const handleErasureConfirm = useCallback((body) => {
    erasureMutation.mutate(
      { subjectType: dsar.subjectType, subjectId: dsar.subjectId, body },
      {
        onSuccess: () => setErasureOpen(false),
        onError: () => {}, // error surfaced in dialog
      }
    )
  }, [dsar, erasureMutation])

  return (
    <div style={{ padding: 'var(--token-space-6)', maxWidth: '900px', margin: '0 auto' }}>
      <PageHeader
        title={`DSAR: ${dsar.requestType}`}
        actions={
          <Button type="button" variant="secondary" onClick={onBack}>
            ← Back to queue
          </Button>
        }
      />

      {/* Countdown banner */}
      {!isTerminal && (
        <div
          style={{
            marginBottom: 'var(--token-space-4)',
            padding: 'var(--token-space-3)',
            borderRadius: 'var(--token-radius-control)',
            background: remainingDays < 0
              ? 'var(--token-danger-subtle)'
              : dsar.atRisk ? 'var(--token-warning-subtle)' : 'var(--token-info-subtle)',
            border: `1px solid ${remainingDays < 0
              ? 'var(--token-danger-default)'
              : dsar.atRisk ? 'var(--token-warning-default)' : 'var(--token-info-default)'}`,
            fontSize: 'var(--token-fs-14)',
            color: remainingDays < 0
              ? 'var(--token-danger-emphasis)'
              : dsar.atRisk ? 'var(--token-warning-emphasis)' : 'var(--token-info-emphasis)',
          }}
        >
          {remainingDays < 0
            ? `⚠ Overdue by ${Math.abs(remainingDays)} days — due date: ${new Date(dsar.dueAt).toLocaleDateString()}`
            : `Due in ${remainingDays} day${remainingDays === 1 ? '' : 's'} (${new Date(dsar.dueAt).toLocaleDateString()})`}
        </div>
      )}

      {/* Summary */}
      <section aria-labelledby="summary-heading" style={sectionStyle}>
        <h2 id="summary-heading" style={sectionHeadingStyle}>Summary</h2>
        <dl style={dlStyle}>
          <DescRow label="ID" value={<code style={{ fontVariantNumeric: 'tabular-nums' }}>{dsar.id}</code>} />
          <DescRow label="Type" value={dsar.requestType} />
          <DescRow label="Subject type" value={dsar.subjectType} />
          <DescRow label="Subject ID" value={<code style={{ fontVariantNumeric: 'tabular-nums' }}>{dsar.subjectId}</code>} />
          <DescRow label="State" value={dsar.state} />
          <DescRow label="Submitted" value={new Date(dsar.submittedAt).toLocaleString()} />
          <DescRow label="Due" value={new Date(dsar.dueAt).toLocaleString()} />
        </dl>
      </section>

      {/* Identity Verification */}
      <section aria-labelledby="identity-heading" style={sectionStyle}>
        <h2 id="identity-heading" style={sectionHeadingStyle}>Identity Verification</h2>
        {dsar.identityVerification?.verifiedAt
          ? (
            <dl style={dlStyle}>
              <DescRow label="Verified at" value={new Date(dsar.identityVerification.verifiedAt).toLocaleString()} />
              <DescRow label="Method" value={dsar.identityVerification.method ?? '—'} />
            </dl>
          )
          : (
            <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', margin: 0 }}>
              Identity not yet verified. Export and erasure are unavailable until the subject&apos;s identity is confirmed.
            </p>
          )
        }
      </section>

      {/* State History */}
      <section aria-labelledby="history-heading" style={sectionStyle}>
        <h2 id="history-heading" style={sectionHeadingStyle}>State History</h2>
        {dsar.stateHistory && dsar.stateHistory.length > 0
          ? (
            <ol style={{ margin: 0, padding: '0 0 0 var(--token-space-4)', listStyle: 'none', display: 'flex', flexDirection: 'column', gap: 'var(--token-space-2)' }}>
              {dsar.stateHistory.map((h, i) => (
                <li key={i} style={{ fontSize: 'var(--token-fs-14)', display: 'flex', gap: 'var(--token-space-3)' }}>
                  <span style={{ color: 'var(--token-text-secondary)', whiteSpace: 'nowrap' }}>{new Date(h.at).toLocaleString()}</span>
                  <span>{h.state.replace(/_/g, ' ')}</span>
                  <span style={{ color: 'var(--token-text-secondary)' }}>by {h.actor}</span>
                </li>
              ))}
            </ol>
          )
          : <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', margin: 0 }}>No state history available.</p>
        }
      </section>

      {/* Export Manifest */}
      <section aria-labelledby="export-heading" style={sectionStyle}>
        <h2 id="export-heading" style={sectionHeadingStyle}>Export Manifest</h2>
        {dsar.exportManifest
          ? (
            <>
              <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', margin: '0 0 var(--token-space-3)' }}>
                Generated: {new Date(dsar.exportManifest.generatedAt).toLocaleString()}
              </p>
              <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 'var(--token-fs-14)' }}>
                <caption style={{ textAlign: 'left', marginBottom: 'var(--token-space-2)', color: 'var(--token-text-secondary)' }}>Export sections and row counts</caption>
                <thead>
                  <tr>
                    <th scope="col" style={thStyle}>Section</th>
                    <th scope="col" style={{ ...thStyle, textAlign: 'right' }}>Rows</th>
                  </tr>
                </thead>
                <tbody>
                  {dsar.exportManifest.sections.map((s, i) => (
                    <tr key={i} style={{ borderBottom: 'var(--token-elevation-border)' }}>
                      <td style={tdStyle}>{s.name}</td>
                      <td style={{ ...tdStyle, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{s.rowCount.toLocaleString()}</td>
                    </tr>
                  ))}
                </tbody>
              </table>

              {!isTerminal && isIdentityVerified && (
                <div style={{ marginTop: 'var(--token-space-4)' }}>
                  {exportError && (
                    <p role="alert" style={{ color: 'var(--token-danger-emphasis)', fontSize: 'var(--token-fs-14)', marginBottom: 'var(--token-space-2)' }}>
                      {exportError}
                    </p>
                  )}
                  <Button
                    type="button"
                    variant="secondary"
                    onClick={handleDownload}
                    disabled={exportUrlMutation.isPending}
                  >
                    {exportUrlMutation.isPending ? 'Preparing download…' : '⬇ Download export'}
                  </Button>
                  <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', marginTop: 'var(--token-space-1)' }}>
                    A fresh short-lived URL is requested on each click.
                  </p>
                </div>
              )}

              {!isIdentityVerified && !isTerminal && (
                <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', marginTop: 'var(--token-space-3)' }}>
                  Export download is unavailable: the subject&apos;s identity has not been verified.
                </p>
              )}
            </>
          )
          : <p style={{ fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)', margin: 0 }}>No export generated yet.</p>
        }
      </section>

      {/* Erasure action — not shown for terminal states */}
      {dsar.requestType === 'ERASURE' && !isTerminal && (
        <section aria-labelledby="erasure-heading" style={sectionStyle}>
          <h2 id="erasure-heading" style={sectionHeadingStyle}>Cryptographic Erasure</h2>
          <p style={{ fontSize: 'var(--token-fs-14)', marginBottom: 'var(--token-space-3)' }}>
            Erasure destroys the subject&apos;s encryption key, permanently rendering all personal data unreadable while retaining non-identifying transaction records.
          </p>
          <Button
            type="button"
            variant="primary"
            onClick={() => setErasureOpen(true)}
            disabled={!isIdentityVerified}
            style={{
              background: isIdentityVerified ? 'var(--token-danger-default)' : undefined,
              borderColor: isIdentityVerified ? 'var(--token-danger-default)' : undefined,
            }}
          >
            Initiate erasure…
          </Button>
          {!isIdentityVerified && (
            <p style={{ fontSize: 'var(--token-fs-13)', color: 'var(--token-text-secondary)', marginTop: 'var(--token-space-1)' }}>
              Identity must be verified before erasure can be initiated.
            </p>
          )}
        </section>
      )}

      <ErasureConfirmDialog
        open={erasureOpen}
        onClose={() => setErasureOpen(false)}
        subjectType={dsar.subjectType}
        subjectId={dsar.subjectId}
        dsarRequestId={dsar.id}
        onConfirm={handleErasureConfirm}
        isPending={erasureMutation.isPending}
        errorMessage={erasureMutation.error?.message ?? null}
      />
    </div>
  )
}

/** @param {{ label: string, value: React.ReactNode }} props */
function DescRow({ label, value }) {
  return (
    <>
      <dt style={{ color: 'var(--token-text-secondary)', fontSize: 'var(--token-fs-14)' }}>{label}</dt>
      <dd style={{ margin: 0, fontSize: 'var(--token-fs-14)' }}>{value}</dd>
    </>
  )
}

const sectionStyle = {
  marginBottom: 'var(--token-space-8)',
  padding: 'var(--token-space-4)',
  background: 'var(--token-surface-card)',
  borderRadius: 'var(--token-radius-card)',
  border: 'var(--token-elevation-border)',
}

const sectionHeadingStyle = {
  fontSize: 'var(--token-fs-16)',
  fontWeight: 600,
  color: 'var(--token-text-primary)',
  margin: '0 0 var(--token-space-4)',
  fontFamily: 'var(--token-family-base)',
}

const dlStyle = {
  margin: 0,
  display: 'grid',
  gridTemplateColumns: 'max-content 1fr',
  gap: 'var(--token-space-1) var(--token-space-6)',
}

const thStyle = {
  padding: 'var(--token-space-2) var(--token-space-3)',
  background: 'var(--token-surface-default)',
  fontWeight: 600,
  textAlign: 'left',
  borderBottom: '2px solid var(--token-border-default)',
  fontFamily: 'var(--token-family-base)',
  color: 'var(--token-text-secondary)',
  fontSize: 'var(--token-fs-13)',
}

const tdStyle = {
  padding: 'var(--token-space-2) var(--token-space-3)',
  color: 'var(--token-text-primary)',
  fontFamily: 'var(--token-family-base)',
}
