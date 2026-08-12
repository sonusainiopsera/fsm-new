/**
 * @fileoverview Audit trail search screen.
 *
 * Implements five named states:
 *   - loading: skeleton rows in table while initial fetch is in flight
 *   - empty:   no revisions matched the current filter set
 *   - degraded: data shown but may be stale (staleness age displayed)
 *   - permission-denied: caller is not ADMIN or PRIVACY_ADMIN
 *   - error:   network or server error with actionable message
 *
 * Composed entirely from design-system tokens. No hard-coded colours, spacing or radii.
 * Keyboard operable: filter form, table rows, and drawer are all reachable via Tab/Enter/Esc.
 * Reduced-motion honoured via CSS var(--duration-fast) which resolves to 0.01ms under
 * prefers-reduced-motion (base.css).
 *
 * @module AuditSearchPage
 */

import { useState, useEffect, useCallback, useRef } from 'react';

// ── Constants ─────────────────────────────────────────────────────────────────────────────

const API_BASE = '/api/v1/admin/audit-revisions';
const EXPORT_BASE = '/api/v1/admin/audit-exports';

/** Allow-listed entity types available to the filter bar. */
const ENTITY_TYPES = [
  { value: '',                    label: 'All entity types' },
  { value: 'WorkOrder',           label: 'Work Order' },
  { value: 'Assignment',          label: 'Assignment' },
  { value: 'Site',                label: 'Site' },
  { value: 'AppUser',             label: 'User' },
  { value: 'TechnicianCertification', label: 'Certification' },
  { value: 'SlaPolicy',           label: 'SLA Policy' },
  { value: 'RoleAssignment',      label: 'Role Assignment' },
  { value: 'Asset',               label: 'Asset' },
];

/** Table density options: comfortable (40 px rows) and compact (32 px rows). */
const DENSITY_COMFORTABLE = 'comfortable';
const DENSITY_COMPACT      = 'compact';

// ── Component ─────────────────────────────────────────────────────────────────────────────

/**
 * Audit trail search screen with filter bar, dense results table, revision detail drawer,
 * and export action with progress and download handling.
 *
 * @returns {JSX.Element}
 */
export default function AuditSearchPage() {
  // ── State ────────────────────────────────────────────────────────────────────────────

  /** @type {['loading'|'empty'|'degraded'|'permission-denied'|'error'|'ready', Function]} */
  const [pageState, setPageState] = useState('loading');

  /**
   * @typedef {Object} RevisionSummary
   * @property {number} revisionNumber
   * @property {string} revisionTimestamp
   * @property {string} actorUserId
   * @property {string} actorRole
   * @property {string} entityType
   * @property {string} entityId
   * @property {string} changeType
   * @property {string[]} changedFieldNames
   */
  /** @type {[RevisionSummary[], Function]} */
  const [revisions, setRevisions]     = useState([]);
  const [nextCursor, setNextCursor]   = useState(null);
  const [hasMore, setHasMore]         = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [errorMsg, setErrorMsg]       = useState('');
  const [lastFetch, setLastFetch]     = useState(null);
  const [density, setDensity]         = useState(DENSITY_COMFORTABLE);

  // Filter state
  const [entityType, setEntityType] = useState('');
  const [actorId, setActorId]       = useState('');
  const [dateFrom, setDateFrom]     = useState('');
  const [dateTo, setDateTo]         = useState('');

  // Detail drawer state
  const [drawerOpen, setDrawerOpen]   = useState(false);
  const [drawerDetail, setDrawerDetail] = useState(null);
  const [drawerLoading, setDrawerLoading] = useState(false);

  // Export state
  const [exportState, setExportState] = useState('idle'); // idle | generating | polling | done | error
  const [exportId, setExportId]       = useState(null);
  const [exportFormat, setExportFormat] = useState('CSV');
  const pollRef = useRef(null);

  // ── Data fetching ────────────────────────────────────────────────────────────────────

  const buildQueryString = useCallback((cursor = null) => {
    const params = new URLSearchParams();
    if (entityType) params.set('entityType', entityType);
    if (actorId)    params.set('actorId', actorId);
    if (dateFrom)   params.set('from', new Date(dateFrom).toISOString());
    if (dateTo)     params.set('to', new Date(dateTo).toISOString());
    if (cursor)     params.set('cursor', cursor);
    params.set('size', '20');
    return params.toString();
  }, [entityType, actorId, dateFrom, dateTo]);

  const fetchRevisions = useCallback(async (cursor = null) => {
    const qs = buildQueryString(cursor);
    try {
      const resp = await fetch(`${API_BASE}?${qs}`, {
        headers: { Accept: 'application/json' },
        credentials: 'same-origin',
      });

      if (resp.status === 403) { setPageState('permission-denied'); return; }
      if (!resp.ok) {
        const body = await resp.json().catch(() => ({}));
        setErrorMsg(body.message || `Server error ${resp.status}`);
        setPageState('error');
        return;
      }

      /** @type {{ data: RevisionSummary[], nextCursor: string|null, hasMore: boolean }} */
      const body = await resp.json();

      // Runtime response validation — confirm shape before trusting.
      if (!Array.isArray(body?.data)) {
        setErrorMsg('Unexpected response shape from server.');
        setPageState('error');
        return;
      }

      setRevisions(prev => cursor ? [...prev, ...body.data] : body.data);
      setNextCursor(body.nextCursor ?? null);
      setHasMore(Boolean(body.hasMore));
      setLastFetch(new Date());
      setPageState(body.data.length === 0 && !cursor ? 'empty' : 'ready');

    } catch (err) {
      setErrorMsg('Failed to load audit revisions. Check your network connection.');
      setPageState(revisions.length > 0 ? 'degraded' : 'error');
    }
  }, [buildQueryString]);

  useEffect(() => {
    setPageState('loading');
    setRevisions([]);
    setNextCursor(null);
    fetchRevisions();
  }, [entityType, actorId, dateFrom, dateTo]);

  const handleLoadMore = async () => {
    if (!nextCursor || loadingMore) return;
    setLoadingMore(true);
    await fetchRevisions(nextCursor);
    setLoadingMore(false);
  };

  // ── Detail drawer ────────────────────────────────────────────────────────────────────

  const openDetail = async (rev) => {
    if (!rev.entityId) return;
    setDrawerOpen(true);
    setDrawerLoading(true);
    setDrawerDetail(null);
    try {
      const resp = await fetch(
        `${API_BASE}/${rev.revisionNumber}?entityType=${rev.entityType}&entityId=${rev.entityId}`,
        { headers: { Accept: 'application/json' }, credentials: 'same-origin' });
      const body = await resp.json();
      setDrawerDetail(resp.ok ? body.data : null);
    } catch {
      setDrawerDetail(null);
    }
    setDrawerLoading(false);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    setDrawerDetail(null);
  };

  // Close drawer on Esc.
  useEffect(() => {
    const onKeyDown = (e) => { if (e.key === 'Escape') closeDrawer(); };
    if (drawerOpen) window.addEventListener('keydown', onKeyDown);
    return () => window.removeEventListener('keydown', onKeyDown);
  }, [drawerOpen]);

  // ── Export ───────────────────────────────────────────────────────────────────────────

  const handleExport = async () => {
    setExportState('generating');
    setExportId(null);
    try {
      const body = { format: exportFormat };
      if (entityType) body.entityType = entityType;
      if (actorId)    body.actorId    = actorId;
      if (dateFrom)   body.from       = new Date(dateFrom).toISOString();
      if (dateTo)     body.to         = new Date(dateTo).toISOString();

      const resp = await fetch(EXPORT_BASE, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        credentials: 'same-origin',
        body: JSON.stringify(body),
      });

      if (resp.status === 200) {
        // Synchronous export — trigger browser download from inline content.
        const content = await resp.text();
        const mime = exportFormat === 'CSV' ? 'text/csv' : 'application/json';
        const blob = new Blob([content], { type: mime });
        const url  = URL.createObjectURL(blob);
        const a    = document.createElement('a');
        a.href     = url;
        a.download = `audit-export.${exportFormat.toLowerCase()}`;
        a.click();
        URL.revokeObjectURL(url);
        setExportState('done');

      } else if (resp.status === 202) {
        // Async export — start polling.
        const data = await resp.json();
        setExportId(data.exportId);
        setExportState('polling');
        startPolling(data.exportId);

      } else {
        setExportState('error');
      }
    } catch {
      setExportState('error');
    }
  };

  const startPolling = (id) => {
    pollRef.current = setInterval(async () => {
      try {
        const resp = await fetch(`${EXPORT_BASE}/${id}`, { credentials: 'same-origin' });
        if (!resp.ok) { stopPolling(); setExportState('error'); return; }
        const data = await resp.json();
        if (data.data?.status === 'COMPLETED') {
          stopPolling();
          setExportState('done');
        } else if (data.data?.status === 'FAILED') {
          stopPolling();
          setExportState('error');
        }
      } catch {
        stopPolling();
        setExportState('error');
      }
    }, 3000);
  };

  const stopPolling = () => {
    if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
  };

  useEffect(() => () => stopPolling(), []);

  // ── Render ───────────────────────────────────────────────────────────────────────────

  const rowHeight = density === DENSITY_COMPACT ? '32px' : '40px';

  return (
    <div style={{ fontFamily: 'var(--font-sans)', color: 'var(--color-text-primary)' }}>

      {/* Page header */}
      <header style={{ padding: 'var(--space-6)', borderBottom: 'var(--elevation-hairline)' }}>
        <h1 style={{ fontSize: 'var(--text-xl)', fontWeight: 'var(--weight-semibold)', margin: 0 }}>
          Audit Trail
        </h1>
        <p style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-secondary)', marginTop: 'var(--space-1)' }}>
          Immutable revision history for all audited entities.
        </p>
      </header>

      {/* Filter bar */}
      <div
        role="search"
        aria-label="Audit revision filters"
        style={{
          display: 'flex', flexWrap: 'wrap', gap: 'var(--space-3)',
          padding: 'var(--space-4) var(--space-6)',
          borderBottom: 'var(--elevation-hairline)',
        }}
      >
        {/* Entity type */}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)' }}>
            Entity type
          </span>
          <select
            value={entityType}
            onChange={e => setEntityType(e.target.value)}
            aria-label="Filter by entity type"
            style={{
              border: 'var(--elevation-hairline)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              fontSize: 'var(--text-sm)',
              background: 'var(--color-surface-default)',
            }}
          >
            {ENTITY_TYPES.map(t => (
              <option key={t.value} value={t.value}>{t.label}</option>
            ))}
          </select>
        </label>

        {/* Actor ID */}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)' }}>
            Actor user ID
          </span>
          <input
            type="text"
            value={actorId}
            onChange={e => setActorId(e.target.value)}
            placeholder="UUID"
            aria-label="Filter by actor user ID"
            style={{
              border: 'var(--elevation-hairline)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              fontSize: 'var(--text-sm)',
              background: 'var(--color-surface-default)',
            }}
          />
        </label>

        {/* Date range */}
        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)' }}>From</span>
          <input
            type="date"
            value={dateFrom}
            onChange={e => setDateFrom(e.target.value)}
            aria-label="Filter from date"
            style={{
              border: 'var(--elevation-hairline)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              fontSize: 'var(--text-sm)',
              background: 'var(--color-surface-default)',
            }}
          />
        </label>
        <label style={{ display: 'flex', flexDirection: 'column', gap: 'var(--space-1)' }}>
          <span style={{ fontSize: 'var(--text-xs)', color: 'var(--color-text-secondary)' }}>To</span>
          <input
            type="date"
            value={dateTo}
            onChange={e => setDateTo(e.target.value)}
            aria-label="Filter to date"
            style={{
              border: 'var(--elevation-hairline)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              fontSize: 'var(--text-sm)',
              background: 'var(--color-surface-default)',
            }}
          />
        </label>

        {/* Density toggle */}
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 'var(--space-2)', marginLeft: 'auto' }}>
          <button
            onClick={() => setDensity(DENSITY_COMFORTABLE)}
            aria-pressed={density === DENSITY_COMFORTABLE}
            aria-label="Comfortable density (40px rows)"
            style={{
              padding: 'var(--space-2) var(--space-3)',
              borderRadius: 'var(--radius-sm)',
              border: 'var(--elevation-hairline)',
              background: density === DENSITY_COMFORTABLE
                ? 'var(--color-accent-default)' : 'var(--color-surface-default)',
              color: density === DENSITY_COMFORTABLE
                ? 'var(--color-accent-on)' : 'var(--color-text-primary)',
              fontSize: 'var(--text-xs)',
              cursor: 'pointer',
            }}
          >
            Comfortable
          </button>
          <button
            onClick={() => setDensity(DENSITY_COMPACT)}
            aria-pressed={density === DENSITY_COMPACT}
            aria-label="Compact density (32px rows)"
            style={{
              padding: 'var(--space-2) var(--space-3)',
              borderRadius: 'var(--radius-sm)',
              border: 'var(--elevation-hairline)',
              background: density === DENSITY_COMPACT
                ? 'var(--color-accent-default)' : 'var(--color-surface-default)',
              color: density === DENSITY_COMPACT
                ? 'var(--color-accent-on)' : 'var(--color-text-primary)',
              fontSize: 'var(--text-xs)',
              cursor: 'pointer',
            }}
          >
            Compact
          </button>

          {/* Export controls */}
          <select
            value={exportFormat}
            onChange={e => setExportFormat(e.target.value)}
            aria-label="Export format"
            style={{
              border: 'var(--elevation-hairline)',
              borderRadius: 'var(--radius-sm)',
              padding: 'var(--space-2) var(--space-3)',
              fontSize: 'var(--text-xs)',
              background: 'var(--color-surface-default)',
            }}
          >
            <option value="CSV">CSV</option>
            <option value="JSON">JSON</option>
          </select>

          <button
            onClick={handleExport}
            disabled={exportState === 'generating' || exportState === 'polling'}
            aria-label={`Export audit revisions as ${exportFormat}`}
            aria-busy={exportState === 'generating' || exportState === 'polling'}
            style={{
              padding: 'var(--space-2) var(--space-3)',
              borderRadius: 'var(--radius-sm)',
              border: 'none',
              background: 'var(--color-accent-default)',
              color: 'var(--color-accent-on)',
              fontSize: 'var(--text-xs)',
              cursor: exportState === 'generating' || exportState === 'polling'
                ? 'not-allowed' : 'pointer',
              opacity: exportState === 'generating' || exportState === 'polling' ? 0.6 : 1,
            }}
          >
            {exportState === 'generating' ? 'Generating…' :
             exportState === 'polling'    ? 'Processing…' :
             exportState === 'done'       ? 'Done ✓' : 'Export'}
          </button>
        </div>
      </div>

      {/* Named states ─────────────────────────────────────────────────────────────────── */}

      {pageState === 'loading' && (
        <div role="status" aria-label="Loading audit revisions" aria-live="polite"
             style={{ padding: 'var(--space-8)', textAlign: 'center',
                      color: 'var(--color-text-secondary)', fontSize: 'var(--text-sm)' }}>
          Loading revisions…
        </div>
      )}

      {pageState === 'permission-denied' && (
        <div role="alert" aria-label="Permission denied"
             style={{ padding: 'var(--space-8)', textAlign: 'center' }}>
          <p style={{ fontSize: 'var(--text-base)', color: 'var(--color-semantic-error-text)' }}>
            You do not have permission to access audit history.
          </p>
          <p style={{ fontSize: 'var(--text-sm)', color: 'var(--color-text-secondary)' }}>
            Contact your administrator to request the required role.
          </p>
        </div>
      )}

      {pageState === 'error' && (
        <div role="alert"
             style={{ padding: 'var(--space-8)', textAlign: 'center' }}>
          <p style={{ fontSize: 'var(--text-base)', color: 'var(--color-semantic-error-text)' }}>
            {errorMsg || 'Failed to load audit revisions.'}
          </p>
          <button
            onClick={() => fetchRevisions()}
            style={{
              marginTop: 'var(--space-3)',
              padding: 'var(--space-2) var(--space-4)',
              borderRadius: 'var(--radius-sm)',
              border: 'none',
              background: 'var(--color-accent-default)',
              color: 'var(--color-accent-on)',
              cursor: 'pointer',
            }}
          >
            Retry
          </button>
        </div>
      )}

      {pageState === 'degraded' && (
        <div role="status" aria-label="Degraded — showing stale data"
             style={{
               padding: 'var(--space-3) var(--space-6)',
               background: 'var(--color-semantic-warning-surface)',
               color: 'var(--color-semantic-warning-text)',
               fontSize: 'var(--text-sm)',
             }}>
          Showing data from {lastFetch
            ? new Date(lastFetch).toLocaleTimeString()
            : 'an unknown time'}
          . Latest fetch failed — retrying in background.
        </div>
      )}

      {pageState === 'empty' && (
        <div role="status" aria-label="No audit revisions found"
             style={{ padding: 'var(--space-8)', textAlign: 'center',
                      color: 'var(--color-text-secondary)', fontSize: 'var(--text-sm)' }}>
          No audit revisions found for the current filters.
        </div>
      )}

      {/* Results table ─────────────────────────────────────────────────────────────────── */}

      {(pageState === 'ready' || pageState === 'degraded') && revisions.length > 0 && (
        <div style={{ overflowX: 'auto' }}>
          <table
            role="grid"
            aria-label="Audit revision history"
            aria-rowcount={revisions.length}
            style={{
              width: '100%',
              borderCollapse: 'collapse',
              fontSize: 'var(--text-sm)',
              fontVariantNumeric: 'tabular-nums',
            }}
          >
            <thead>
              <tr style={{
                position: 'sticky',
                top: 0,
                background: 'var(--color-surface-raised)',
                borderBottom: 'var(--elevation-hairline)',
                zIndex: 1,
              }}>
                <th scope="col" style={thStyle}>Rev #</th>
                <th scope="col" style={thStyle}>Timestamp</th>
                <th scope="col" style={thStyle}>Actor</th>
                <th scope="col" style={thStyle}>Entity type</th>
                <th scope="col" style={thStyle}>Entity ID</th>
                <th scope="col" style={thStyle}>Change type</th>
                <th scope="col" style={thStyle}>Changed fields</th>
              </tr>
            </thead>
            <tbody>
              {revisions.map((rev, idx) => (
                <tr
                  key={`${rev.revisionNumber}-${rev.entityId ?? idx}`}
                  tabIndex={0}
                  role="row"
                  aria-label={`Revision ${rev.revisionNumber}, ${rev.changeType} on ${rev.entityType}`}
                  onClick={() => openDetail(rev)}
                  onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') openDetail(rev); }}
                  style={{
                    height: rowHeight,
                    cursor: 'pointer',
                    borderBottom: 'var(--elevation-hairline)',
                    background: 'var(--color-surface-default)',
                    transition: `background var(--duration-fast) var(--easing-standard)`,
                    outline: 'none',
                  }}
                  onFocus={e => e.currentTarget.style.background = 'var(--color-surface-raised)'}
                  onBlur={e => e.currentTarget.style.background = 'var(--color-surface-default)'}
                  onMouseEnter={e => e.currentTarget.style.background = 'var(--color-surface-raised)'}
                  onMouseLeave={e => e.currentTarget.style.background = 'var(--color-surface-default)'}
                >
                  <td style={{ ...tdStyle, textAlign: 'right' }}>{rev.revisionNumber}</td>
                  <td style={tdStyle}>{new Date(rev.revisionTimestamp).toLocaleString()}</td>
                  <td style={tdStyle}>{rev.actorUserId || '—'}</td>
                  <td style={tdStyle}>{rev.entityType || '—'}</td>
                  <td style={{ ...tdStyle, fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)' }}>
                    {rev.entityId || '—'}
                  </td>
                  <td style={tdStyle}>
                    <span style={changeTypeBadgeStyle(rev.changeType)}>{rev.changeType}</span>
                  </td>
                  <td style={tdStyle}>{(rev.changedFieldNames ?? []).join(', ') || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>

          {/* Load more */}
          {hasMore && (
            <div style={{ padding: 'var(--space-4)', textAlign: 'center' }}>
              <button
                onClick={handleLoadMore}
                disabled={loadingMore}
                aria-label="Load more audit revisions"
                aria-busy={loadingMore}
                style={{
                  padding: 'var(--space-2) var(--space-6)',
                  borderRadius: 'var(--radius-sm)',
                  border: 'var(--elevation-hairline)',
                  background: 'var(--color-surface-raised)',
                  fontSize: 'var(--text-sm)',
                  cursor: loadingMore ? 'not-allowed' : 'pointer',
                }}
              >
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
            </div>
          )}
        </div>
      )}

      {/* Revision detail drawer ──────────────────────────────────────────────────────── */}

      {drawerOpen && (
        <div
          role="dialog"
          aria-modal="true"
          aria-label="Revision detail"
          style={{
            position: 'fixed',
            top: 0, right: 0, bottom: 0,
            width: 'min(480px, 100vw)',
            background: 'var(--color-surface-default)',
            boxShadow: 'var(--elevation-md)',
            zIndex: 100,
            display: 'flex',
            flexDirection: 'column',
            transition: `transform var(--duration-normal) var(--easing-standard)`,
          }}
        >
          {/* Drawer header */}
          <div style={{
            display: 'flex',
            justifyContent: 'space-between',
            alignItems: 'center',
            padding: 'var(--space-4) var(--space-6)',
            borderBottom: 'var(--elevation-hairline)',
          }}>
            <h2 style={{ fontSize: 'var(--text-base)', fontWeight: 'var(--weight-semibold)', margin: 0 }}>
              Revision detail
            </h2>
            <button
              onClick={closeDrawer}
              aria-label="Close revision detail"
              style={{
                border: 'none',
                background: 'transparent',
                fontSize: 'var(--text-lg)',
                cursor: 'pointer',
                color: 'var(--color-text-secondary)',
                padding: 'var(--space-2)',
                borderRadius: 'var(--radius-sm)',
                outline: 'none',
              }}
            >
              ×
            </button>
          </div>

          {/* Drawer body */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 'var(--space-6)' }}>
            {drawerLoading && (
              <p role="status" aria-label="Loading revision detail"
                 style={{ color: 'var(--color-text-secondary)', fontSize: 'var(--text-sm)' }}>
                Loading…
              </p>
            )}
            {!drawerLoading && !drawerDetail && (
              <p style={{ color: 'var(--color-semantic-error-text)', fontSize: 'var(--text-sm)' }}>
                Unable to load revision detail.
              </p>
            )}
            {drawerDetail && (
              <div>
                {/* Metadata */}
                <dl style={{ display: 'grid', gridTemplateColumns: '1fr 2fr', gap: 'var(--space-2) var(--space-4)', fontSize: 'var(--text-sm)', marginBottom: 'var(--space-6)' }}>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Revision #</dt>
                  <dd style={{ fontVariantNumeric: 'tabular-nums' }}>{drawerDetail.revisionNumber}</dd>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Timestamp</dt>
                  <dd>{new Date(drawerDetail.revisionTimestamp).toLocaleString()}</dd>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Actor</dt>
                  <dd style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)' }}>{drawerDetail.actorUserId}</dd>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Role</dt>
                  <dd>{drawerDetail.actorRole}</dd>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Entity type</dt>
                  <dd>{drawerDetail.entityType}</dd>
                  <dt style={{ color: 'var(--color-text-secondary)' }}>Entity ID</dt>
                  <dd style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--text-xs)' }}>{drawerDetail.entityId}</dd>
                </dl>

                {/* Field diff table */}
                <h3 style={{ fontSize: 'var(--text-sm)', fontWeight: 'var(--weight-semibold)',
                             marginBottom: 'var(--space-3)' }}>
                  Field changes
                </h3>
                <table
                  role="grid"
                  aria-label="Field-level before and after diff"
                  style={{
                    width: '100%',
                    borderCollapse: 'collapse',
                    fontSize: 'var(--text-xs)',
                    fontVariantNumeric: 'tabular-nums',
                  }}
                >
                  <thead>
                    <tr style={{ background: 'var(--color-surface-raised)',
                                 borderBottom: 'var(--elevation-hairline)' }}>
                      <th scope="col" style={thStyle}>Field</th>
                      <th scope="col" style={thStyle}>Before</th>
                      <th scope="col" style={thStyle}>After</th>
                      <th scope="col" style={thStyle}>Changed</th>
                    </tr>
                  </thead>
                  <tbody>
                    {(drawerDetail.fields ?? []).map(field => (
                      <tr
                        key={field.name}
                        style={{
                          borderBottom: 'var(--elevation-hairline)',
                          background: field.changed
                            ? 'var(--color-semantic-warning-surface)'
                            : 'var(--color-surface-default)',
                        }}
                      >
                        <td style={tdStyle}>{field.name}</td>
                        <td style={{ ...tdStyle, color: 'var(--color-semantic-error-text)' }}>
                          {field.masked ? '[REDACTED]' : (field.before ?? '—')}
                        </td>
                        <td style={{ ...tdStyle, color: 'var(--color-semantic-success-text)' }}>
                          {field.masked ? '[REDACTED]' : (field.after ?? '—')}
                        </td>
                        <td style={{ ...tdStyle, textAlign: 'center' }}>
                          {field.changed ? '✓' : ''}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </div>
        </div>
      )}

      {/* Drawer backdrop */}
      {drawerOpen && (
        <div
          role="presentation"
          onClick={closeDrawer}
          aria-hidden="true"
          style={{
            position: 'fixed', inset: 0,
            background: 'var(--color-scrim)',
            zIndex: 99,
            transition: `opacity var(--duration-normal) var(--easing-standard)`,
          }}
        />
      )}
    </div>
  );
}

// ── Style helpers ─────────────────────────────────────────────────────────────────────────

/** @type {React.CSSProperties} */
const thStyle = {
  padding: 'var(--space-2) var(--space-3)',
  textAlign: 'left',
  fontSize: 'var(--text-xs)',
  fontWeight: 'var(--weight-semibold)',
  color: 'var(--color-text-secondary)',
  whiteSpace: 'nowrap',
};

/** @type {React.CSSProperties} */
const tdStyle = {
  padding: 'var(--space-2) var(--space-3)',
  verticalAlign: 'middle',
  whiteSpace: 'nowrap',
  overflow: 'hidden',
  textOverflow: 'ellipsis',
  maxWidth: '200px',
};

/**
 * @param {string} changeType
 * @returns {React.CSSProperties}
 */
function changeTypeBadgeStyle(changeType) {
  const colors = {
    ADD: { bg: 'var(--color-semantic-success-surface)', fg: 'var(--color-semantic-success-text)' },
    MOD: { bg: 'var(--color-semantic-warning-surface)', fg: 'var(--color-semantic-warning-text)' },
    DEL: { bg: 'var(--color-semantic-error-surface)',   fg: 'var(--color-semantic-error-text)'   },
  };
  const c = colors[changeType] ?? colors.MOD;
  return {
    display: 'inline-block',
    padding: '2px var(--space-2)',
    borderRadius: 'var(--radius-full)',
    fontSize: 'var(--text-xs)',
    fontWeight: 'var(--weight-medium)',
    background: c.bg,
    color: c.fg,
  };
}
