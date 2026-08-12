/**
 * TechnicianCertificationsPage — view/manage certifications per technician.
 *
 * Certification currency (current / expiring-soon / expired) is rendered
 * EXCLUSIVELY from the API-derived `current` and `daysUntilExpiry` fields.
 * The client performs NO date math whatsoever.
 *
 * Also provides a CSV import entry point for bulk upsert.
 *
 * @module features/admin/TechnicianCertificationsPage
 */

import React, { useState } from 'react';
import { useQuery } from '@tanstack/react-query';

import {
  PageHeader, DataTable, Button, EmptyState, LoadingState, ErrorState,
} from '../../components/index.js';
import { listTechnicianCertifications } from '../../api/workforce.js';

import { CertificationChip } from './CertificationChip.jsx';
import { PaginationBar }     from './PaginationBar.jsx';
import { CsvImportWizard }   from './CsvImportWizard.jsx';
import styles from './admin.module.css';

const COLUMNS = [
  { key: 'typeCode',             header: 'Type code',    sortable: false },
  { key: 'typeDisplayName',      header: 'Name',         sortable: false },
  { key: 'certificateReference', header: 'Reference',    sortable: false,
    render: (v) => v ?? '—' },
  { key: 'issuedOn',    header: 'Issued',    sortable: false, render: (v) => v ?? '—' },
  { key: 'expiresOn',   header: 'Expires',   sortable: false, render: (v) => v ?? 'Never' },
  {
    key: 'current',
    header: 'Status',
    sortable: false,
    render: (v, row) => (
      <CertificationChip
        current={v}
        daysUntilExpiry={row.daysUntilExpiry}
        expiresOn={row.expiresOn}
      />
    ),
  },
];

/**
 * @param {{ roles?: string[] }} props
 */
export function TechnicianCertificationsPage({ roles = [] }) {
  const isAdmin = roles.some((r) => ['ADMIN', 'MANAGER'].includes(r));
  const [technicianId, setTechnicianId] = useState('');
  const [submittedId, setSubmittedId] = useState('');
  const [page, setPage] = useState(0);
  const [importOpen, setImportOpen] = useState(false);

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ['technician-certifications', submittedId, page],
    queryFn: () => listTechnicianCertifications(submittedId, { page }),
    enabled: !!submittedId,
  });

  const certData   = Array.isArray(data?.data) ? data.data : [];
  const pageMeta   = data?.page ?? { number: 0, size: 20, totalElements: 0, totalPages: 1 };

  function handleSearch(e) {
    e.preventDefault();
    setPage(0);
    setSubmittedId(technicianId.trim());
  }

  return (
    <div className={styles.page}>
      <PageHeader title="Technician Certifications">
        {isAdmin && (
          <Button onClick={() => setImportOpen(true)}>Import CSV</Button>
        )}
      </PageHeader>

      <form onSubmit={handleSearch} className={styles.toolbar}>
        <input
          type="text"
          className={styles.searchInput}
          placeholder="Enter technician ID…"
          aria-label="Technician ID"
          value={technicianId}
          onChange={(e) => setTechnicianId(e.target.value)}
        />
        <Button type="submit">View certifications</Button>
      </form>

      {!submittedId && (
        <EmptyState title="Enter a technician ID" description="Enter the technician's ID above to view their certifications." />
      )}

      {submittedId && isLoading && <LoadingState />}
      {submittedId && isError && <ErrorState description={error?.message} />}

      {submittedId && !isLoading && !isError && (
        <>
          <DataTable
            columns={COLUMNS}
            data={certData}
            rowKey={(r) => r.id ?? r.typeCode}
            emptyState={<EmptyState title="No certifications" description="This technician has no certifications recorded." />}
          />
          <PaginationBar
            page={pageMeta.number}
            totalPages={pageMeta.totalPages}
            totalElements={pageMeta.totalElements}
            onPageChange={setPage}
          />
        </>
      )}

      {isAdmin && (
        <CsvImportWizard
          open={importOpen}
          onClose={() => setImportOpen(false)}
          mode="certifications"
          technicianId={submittedId || undefined}
        />
      )}
    </div>
  );
}
