/**
 * @fileoverview ServiceRequestStatusPage — portal service request live status tracking.
 *
 * Polls every 60 seconds via useConditionalQuery (ETag / If-None-Match).
 * A 304 retains the cached data without a loading skeleton re-render (AC-4).
 * Freshness and degraded states are shown via FreshnessBanner (AC-5).
 * No internal state codes, coordinates, or technician PII appear in the DOM (AC-6).
 */

import { useParams, useLocation } from 'react-router-dom'
import { PageHeader, LoadingState, ErrorState } from '../../components/index.js'
import { useServiceRequestStatus } from '../../api/portalClient.js'
import { StatusTimeline } from '../../components/StatusTimeline/StatusTimeline.jsx'
import { FreshnessBanner } from '../../components/FreshnessBanner/FreshnessBanner.jsx'
import { useAppearance } from '../../appearance/AppearanceProvider.jsx'

/**
 * Formats an ISO timestamp as a plain-language date/time string.
 * @param {string | null | undefined} iso
 * @returns {string}
 */
function formatDeadline(iso) {
  if (!iso) return 'Not set'
  try {
    return new Date(iso).toLocaleString(undefined, {
      weekday: 'short',
      year: 'numeric',
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return iso
  }
}

export default function ServiceRequestStatusPage() {
  const { id } = useParams()
  const { state: navState } = useLocation()
  const { preference, setPreference } = useAppearance()

  const {
    data: response,
    isLoading,
    isError,
    refetch,
  } = useServiceRequestStatus(id)

  if (isLoading && !response) {
    return <LoadingState />
  }

  const status = response?.data

  if (isError && !status) {
    return (
      <main
        style={{
          maxWidth: 640,
          margin: '0 auto',
          padding: 'var(--token-space-8)',
          fontFamily: 'var(--token-family-base)',
        }}
      >
        <FreshnessBanner isError onRetry={refetch} />
      </main>
    )
  }

  const reference = status?.reference ?? navState?.reference
  const freshness = status?.freshness ?? null

  return (
    <main
      style={{
        maxWidth: 640,
        margin: '0 auto',
        padding: 'var(--token-space-8)',
        fontFamily: 'var(--token-family-base)',
        display: 'flex',
        flexDirection: 'column',
        gap: 'var(--token-space-6)',
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', flexWrap: 'wrap', gap: 'var(--token-space-4)' }}>
        <PageHeader
          title={reference ? `Request ${reference}` : 'Request Status'}
        />
        <button
          type="button"
          aria-label={`Switch to ${preference === 'DARK' ? 'light' : 'dark'} appearance`}
          onClick={() => setPreference(preference === 'DARK' ? 'LIGHT' : 'DARK')}
          style={{
            background: 'var(--token-surface-1)',
            border: '1px solid var(--token-border-default)',
            borderRadius: 'var(--token-radius-sm)',
            padding: 'var(--token-space-2) var(--token-space-4)',
            cursor: 'pointer',
            fontSize: 'var(--token-fs-14)',
            color: 'var(--token-text-primary)',
            minHeight: '44px',
          }}
        >
          {preference === 'DARK' ? 'Light' : 'Dark'} appearance
        </button>
      </div>

      {/* Freshness / degraded / not-connected banner */}
      {isError
        ? <FreshnessBanner isError onRetry={refetch} />
        : <FreshnessBanner freshness={freshness} />
      }

      {status && (
        <>
          {/* Status timeline */}
          <StatusTimeline
            currentLabel={status.statusLabel}
            currentDescription={status.statusDescription}
            milestones={status.milestones ?? []}
          />

          {/* Appointment window */}
          {status.appointmentWindow && (
            <section
              aria-label="Scheduled appointment"
              style={{
                padding: 'var(--token-space-6)',
                background: 'var(--token-surface-1)',
                borderRadius: 'var(--token-radius-md)',
                border: '1px solid var(--token-border-subtle)',
              }}
            >
              <h2
                style={{
                  fontSize: 'var(--token-fs-16)',
                  fontWeight: 600,
                  margin: '0 0 var(--token-space-3) 0',
                  color: 'var(--token-text-primary)',
                }}
              >
                Scheduled visit
              </h2>
              <p
                style={{
                  margin: 0,
                  fontSize: 'var(--token-fs-16)',
                  color: 'var(--token-text-primary)',
                }}
              >
                {formatDeadline(status.appointmentWindow.fromAt)}
                {' — '}
                {formatDeadline(status.appointmentWindow.toAt)}
              </p>
            </section>
          )}

          {/* Technician info (first name + role only — no PII) */}
          {status.technician && (
            <section
              aria-label="Assigned technician"
              style={{
                padding: 'var(--token-space-6)',
                background: 'var(--token-surface-1)',
                borderRadius: 'var(--token-radius-md)',
                border: '1px solid var(--token-border-subtle)',
              }}
            >
              <h2
                style={{
                  fontSize: 'var(--token-fs-16)',
                  fontWeight: 600,
                  margin: '0 0 var(--token-space-3) 0',
                  color: 'var(--token-text-primary)',
                }}
              >
                Your technician
              </h2>
              <p
                style={{
                  margin: 0,
                  fontSize: 'var(--token-fs-16)',
                  color: 'var(--token-text-primary)',
                }}
              >
                {status.technician.firstName} · {status.technician.roleLabel}
              </p>
            </section>
          )}

          {/* Resolution commitment */}
          {status.resolveByAt && (
            <section
              aria-label="Resolution commitment"
              style={{
                padding: 'var(--token-space-6)',
                background: 'var(--token-surface-1)',
                borderRadius: 'var(--token-radius-md)',
                border: '1px solid var(--token-border-subtle)',
              }}
            >
              <h2
                style={{
                  fontSize: 'var(--token-fs-16)',
                  fontWeight: 600,
                  margin: '0 0 var(--token-space-3) 0',
                  color: 'var(--token-text-primary)',
                }}
              >
                Resolution target
              </h2>
              <p
                style={{
                  margin: 0,
                  fontSize: 'var(--token-fs-16)',
                  color: 'var(--token-text-primary)',
                }}
              >
                {formatDeadline(status.resolveByAt)}
              </p>
            </section>
          )}
        </>
      )}
    </main>
  )
}
