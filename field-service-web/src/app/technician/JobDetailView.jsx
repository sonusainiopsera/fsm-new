/**
 * JobDetailView — technician job detail screen.
 *
 * Delivers site/contact/asset/fault context in a single mobile screen with:
 * - SlaCountdownChip updating every 30s
 * - TransitionActionBar driven purely by server allowedTransitions
 * - AssetHistoryList with last 5 prior service records
 * - ContextCard with expandable access notes and fault description
 *
 * Error handling:
 * - 404 → not-found state
 * - 403 → permission denied state (no existence disclosure)
 * - Network failure → error state with retry
 *
 * @module app/technician/JobDetailView
 */

import React, { useCallback, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { useJobDetail } from './useJobDetail.js';
import { usePositionReporting } from './hooks/usePositionReporting.js';
import { ContextCard } from './components/ContextCard.jsx';
import { AssetHistoryList } from './components/AssetHistoryList.jsx';
import { SlaCountdownChip } from './components/SlaCountdownChip.jsx';
import { TransitionActionBar } from './components/TransitionActionBar.jsx';
import { LoadingState, EmptyState, ErrorState } from '../../components/index.js';
import { CopilotSheet } from '../../features/copilot/CopilotSheet.jsx';
import copilotStyles from '../../features/copilot/CopilotSheet.module.css';
import styles from './JobDetailView.module.css';

export function JobDetailView() {
  const { jobId }   = useParams();
  const navigate    = useNavigate();
  const qc          = useQueryClient();

  const { data: job, isLoading, isError, error, refetch } = useJobDetail(jobId);

  const [copilotOpen, setCopilotOpen] = useState(false);

  // Start position reporting when job is EN_ROUTE or IN_PROGRESS; stops on unmount
  usePositionReporting(job?.state ?? null, jobId);

  const handleTransitionSuccess = useCallback(() => {
    qc.invalidateQueries({ queryKey: ['technician', 'jobs', jobId] });
    qc.invalidateQueries({ queryKey: ['technician', 'day-list'] });
  }, [qc, jobId]);

  if (isLoading) return <LoadingState />;

  if (isError) {
    if (error?.status === 404) {
      return (
        <EmptyState
          title="Job not found"
          description="This job may have been reassigned or cancelled."
          action={{ label: 'Back to jobs', onClick: () => navigate('/technician') }}
        />
      );
    }
    if (error?.status === 403) {
      return (
        <EmptyState
          title="Not authorised"
          description="You do not have access to this job."
          action={{ label: 'Back to jobs', onClick: () => navigate('/technician') }}
        />
      );
    }
    return (
      <ErrorState
        title="Couldn't load job"
        description="Please check your connection and try again."
        action={{ label: 'Retry', onClick: refetch }}
      />
    );
  }

  if (!job) return <LoadingState />;

  return (
    <div className={styles.screen}>
      {/* Header bar */}
      <header className={styles.header}>
        <button
          type="button"
          className={styles.backBtn}
          onClick={() => navigate('/technician')}
          aria-label="Back to jobs"
        >
          ‹
        </button>
        <span className={styles.ref}>{job.reference}</span>
        <SlaCountdownChip
          resolutionDeadline={job.resolutionDeadline}
          slaAtRisk={job.slaAtRisk}
        />
      </header>

      {/* State pill */}
      <div className={styles.statePill} aria-label={`Status: ${job.state}`}>
        <span className={`${styles.dot} ${styles[`dot_${job.state}`]}`} aria-hidden="true" />
        {job.state.replace('_', ' ')}
      </div>

      {/* Scrollable content */}
      <main className={styles.content}>
        <ContextCard job={job} />
        <AssetHistoryList assetId={job.assetId} />
      </main>

      {/* Sticky action bar */}
      <TransitionActionBar
        workOrderId={job.id}
        expectedVersion={job.version}
        allowedTransitions={job.allowedTransitions ?? []}
        holdReasons={job.holdReasons ?? []}
        onSuccess={handleTransitionSuccess}
      />

      {/* Copilot FAB — only rendered when server capability flag is on */}
      {job.copilotEnabled === true && (
        <div className={styles.copilotFabContainer}>
          <button
            type="button"
            className={copilotStyles.copilotFab}
            onClick={() => setCopilotOpen(true)}
            aria-label="Open copilot assistant"
          >
            ✦ Copilot
          </button>
        </div>
      )}

      <CopilotSheet
        open={copilotOpen}
        workOrderId={job.id}
        onClose={() => setCopilotOpen(false)}
      />
    </div>
  );
}
