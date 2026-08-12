/**
 * DsarRequestDetailPage — detail view for a single DSAR request.
 *
 * Shows state, identity-verification record, export manifest with per-section counts.
 * Download action requests a fresh short-lived URL on every click; URL is never persisted.
 * Erasure action opens ErasureConfirmDialog. Export is disabled with explanation if
 * the request is not in a downloadable state.
 *
 * @module features/privacy/DsarRequestDetailPage
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import {
  PageHeader, Button, LoadingState, ErrorState, PermissionDeniedState,
} from '../../components/index.js';
import { getDsarRequest, getDsarExportUrl } from '../../api/privacyAdmin.js';
import { ErasureConfirmDialog } from './ErasureConfirmDialog.jsx';

import styles from '../admin/admin.module.css';

const DOWNLOADABLE_STATES = new Set(['FULFILLED']);
const ERASURE_ELIGIBLE_STATES = new Set(['VERIFIED']);
const TERMINAL_STATES = new Set(['FULFILLED', 'REJECTED', 'WITHDRAWN']);

/**
 * @param {{ dsarId: string, roles?: string[], onBack?: () => void }} props
 */
export function DsarRequestDetailPage({ dsarId, roles = [], onBack }) {
  const [downloadError, setDownloadError] = useState(null);
  const [erasureOpen, setErasureOpen] = useState(false);
  const [erasureSuccess, setErasureSuccess] = useState(null);

  const { data: request, isLoading, isError, error, refetch } = useQuery({
    queryKey: ['privacy', 'dsar-request', dsarId],
    queryFn:  () => getDsarRequest(dsarId),
    enabled:  !!dsarId,
  });

  const handleDownload = async () => {
    setDownloadError(null);
    try {
      // Request a fresh short-lived URL on every click — never cache the URL
      const result = await getDsarExportUrl(dsarId);
      const url = result?.downloadUrl ?? result?.downloadToken;
      if (!url) throw new Error('No download URL in response');
      // Open in new tab to prevent storing in client state
      window.open(url, '_blank', 'noopener,noreferrer');
    } catch (err) {
      setDownloadError(err?.message ?? 'Download failed. Please try again.');
    }
  };

  const handleErasureSuccess = (result) => {
    setErasureSuccess(result);
    refetch();
  };

  if (isLoading) return <LoadingState />;
  if (isError) {
    const status = error?.status ?? error?.statusCode;
    if (status === 403) return <PermissionDeniedState />;
    if (status === 404) return <ErrorState title="Request not found" description="The DSAR request could not be found." />;
    return <ErrorState onRetry={refetch} description="Could not load DSAR request." />;
  }

  const isDownloadable = DOWNLOADABLE_STATES.has(request?.state);
  const isErasureEligible = ERASURE_ELIGIBLE_STATES.has(request?.state) && request?.requestType === 'ERASURE';
  const isTerminal = TERMINAL_STATES.has(request?.state);

  const exportDisabledReason = !request?.identityVerifiedAt
    ? 'Export is unavailable: identity has not been verified for this request.'
    : !isDownloadable
    ? `Export is only available for FULFILLED requests (current state: ${request?.state}).`
    : null;

  return (
    <div className={styles.page}>
      <PageHeader
        title={`DSAR — ${request?.requestType ?? ''}`}
        description={`Subject: ${request?.subjectType} / ${request?.subjectId}`}
        actions={
          <>
            {onBack && (
              <Button variant="secondary" onClick={onBack}>← Back to queue</Button>
            )}
            {isErasureEligible && (
              <Button variant="danger" onClick={() => setErasureOpen(true)}>
                Initiate erasure
              </Button>
            )}
            {isDownloadable && (
              <Button
                variant="secondary"
                onClick={handleDownload}
                disabled={!!exportDisabledReason}
                title={exportDisabledReason ?? undefined}
              >
                Download export
              </Button>
            )}
          </>
        }
      />

      {downloadError && (
        <div className={styles.errorText} role="alert">{downloadError}</div>
      )}
      {exportDisabledReason && (
        <p className={styles.infoNotice} role="note">{exportDisabledReason}</p>
      )}
      {erasureSuccess && (
        <div className={styles.successNotice} role="status">
          Erasure initiated. Erasure ID: <code>{erasureSuccess.erasureId}</code>. Status: {erasureSuccess.state}.
        </div>
      )}

      <section aria-labelledby="dsar-details-heading">
        <h2 id="dsar-details-heading" className={styles.sectionHeading}>Request details</h2>
        <dl className={styles.detailGrid}>
          <dt>State</dt><dd>{request?.state}</dd>
          <dt>Request type</dt><dd>{request?.requestType}</dd>
          <dt>Submitted</dt><dd>{request?.submittedAt ? new Date(request.submittedAt).toLocaleString() : '—'}</dd>
          <dt>Due</dt><dd>{request?.dueAt ? new Date(request.dueAt).toLocaleString() : '—'}</dd>
          <dt>Remaining days</dt>
          <dd className={request?.atRisk ? styles.countdownAtRisk : undefined}>
            {request?.remainingDays ?? '—'}
          </dd>
          <dt>Outcome</dt><dd>{request?.outcome ?? '—'}</dd>
        </dl>
      </section>

      {request?.identityVerifiedAt && (
        <section aria-labelledby="dsar-identity-heading">
          <h2 id="dsar-identity-heading" className={styles.sectionHeading}>Identity verification</h2>
          <dl className={styles.detailGrid}>
            <dt>Verified at</dt><dd>{new Date(request.identityVerifiedAt).toLocaleString()}</dd>
            <dt>Method</dt><dd>{request.verificationMethod ?? '—'}</dd>
          </dl>
        </section>
      )}

      {request?.manifest && (
        <section aria-labelledby="dsar-manifest-heading">
          <h2 id="dsar-manifest-heading" className={styles.sectionHeading}>Export manifest</h2>
          <table className={styles.manifestTable} aria-label="Export manifest sections">
            <caption className={styles.sr_only}>Export manifest — sections and row counts</caption>
            <thead>
              <tr>
                <th scope="col">Section</th>
                <th scope="col">Module</th>
                <th scope="col" className={styles.numericCol}>Row count</th>
              </tr>
            </thead>
            <tbody>
              {(request.manifest ?? []).map((entry, i) => (
                <tr key={i}>
                  <td>{entry.sectionName}</td>
                  <td>{entry.sourceModule}</td>
                  <td className={[styles.numericCol, styles.tabularFigure].join(' ')}>{entry.rowCount}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <ErasureConfirmDialog
        open={erasureOpen}
        dsarRequest={request}
        onClose={() => setErasureOpen(false)}
        onSuccess={handleErasureSuccess}
      />
    </div>
  );
}
