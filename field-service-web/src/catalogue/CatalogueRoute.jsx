/**
 * @fileoverview CatalogueRoute — fixture-driven catalogue of every primitive and named state.
 * Buildable in CI as a static artifact for visual review without a backend.
 */
import { useState } from 'react'
import {
  Button, PageHeader, KpiCard, DataTable, DensityToggle,
  DetailDrawer, Modal, FormField, Chip, ScorePresentation,
  ToastProvider, useToast,
  EmptyState, LoadingState, DegradedState, PermissionDeniedState, ErrorState,
} from '../components/index.js'
import { DensityProvider } from '../density/DensityContext.js'
import managerKpis from '../mocks/fixtures/manager-kpis.json'
import dispatcherWorkOrders from '../mocks/fixtures/dispatcher-workorders.json'

const WO_COLUMNS = [
  { key: 'id', header: 'ID', sortable: true },
  { key: 'title', header: 'Title', sortable: true },
  { key: 'priority', header: 'Priority', render: (v) => <Chip kind="priority" value={String(v)} /> },
  { key: 'state', header: 'State', render: (v) => <Chip kind="state" value={String(v)} /> },
  { key: 'site', header: 'Site', sortable: true },
]

function ToastDemo() {
  const { toast } = useToast()
  return (
    <div style={{ display: 'flex', gap: 'var(--token-space-3)', flexWrap: 'wrap' }}>
      {(['info', 'success', 'warning', 'danger']).map(v => (
        <Button key={v} variant="secondary" onClick={() => toast(`This is a ${v} toast message.`, v)}>
          {v} toast
        </Button>
      ))}
    </div>
  )
}

export function CatalogueRoute() {
  const [modalOpen, setModalOpen] = useState(false)
  const [drawerOpen, setDrawerOpen] = useState(false)

  return (
    <ToastProvider>
      <DensityProvider>
        <div style={{ fontFamily: 'var(--token-family-base)', maxWidth: 'var(--token-content-max-width)', margin: '0 auto', padding: 'var(--token-gutter)' }}>
          <h1 style={{ fontSize: 'var(--token-fs-30)', color: 'var(--token-text-primary)', marginBottom: 'var(--token-space-8)' }}>
            Component Catalogue
          </h1>

          <Section title="PageHeader">
            <PageHeader
              title="Work Orders"
              breadcrumbs={[{ label: 'Home', href: '#' }, { label: 'Work Orders' }]}
              primaryAction={{ label: 'New Work Order', onClick: () => {} }}
              secondaryActions={[{ label: 'Export', onClick: () => {} }]}
            />
          </Section>

          <Section title="Button">
            <div style={{ display: 'flex', gap: 'var(--token-space-3)', flexWrap: 'wrap', alignItems: 'center' }}>
              {['primary', 'secondary', 'tertiary', 'ghost', 'destructive'].map(v => (
                <Button key={v} variant={v}>{v}</Button>
              ))}
              <Button variant="primary" disabled>disabled</Button>
              <Button variant="primary" touchTarget>touch target</Button>
            </div>
          </Section>

          <Section title="Chip">
            <div style={{ display: 'flex', gap: 'var(--token-space-3)', flexWrap: 'wrap' }}>
              {['critical', 'high', 'medium', 'low'].map(v => <Chip key={v} kind="priority" value={v} />)}
              {['new', 'assigned', 'en_route', 'in_progress', 'on_hold', 'completed', 'closed', 'cancelled'].map(v => (
                <Chip key={v} kind="state" value={v} />
              ))}
              {['high', 'medium', 'low'].map(v => <Chip key={v} kind="risk" value={v} />)}
              <Chip kind="state" value="unknown_value" />
            </div>
          </Section>

          <Section title="KpiCard">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(240px, 1fr))', gap: 'var(--token-space-4)' }}>
              {managerKpis.kpis.map(kpi => (
                <KpiCard key={kpi.id} {...kpi} />
              ))}
              <KpiCard label="No Delta" value="999" />
            </div>
          </Section>

          <Section title="DataTable">
            <div style={{ display: 'flex', justifyContent: 'flex-end', marginBottom: 'var(--token-space-3)' }}>
              <DensityToggle />
            </div>
            <DataTable
              columns={WO_COLUMNS}
              rows={dispatcherWorkOrders.workOrders}
              rowKey={r => String(r.id)}
              aria-label="Work orders table"
            />
          </Section>

          <Section title="DataTable – Empty state">
            <DataTable columns={WO_COLUMNS} rows={[]} rowKey={r => String(r.id)} aria-label="Empty table" />
          </Section>

          <Section title="FormField">
            <div style={{ maxWidth: '400px', display: 'flex', flexDirection: 'column', gap: 'var(--token-space-6)' }}>
              <FormField label="Work Order Title" required helpText="Brief summary of the issue.">
                {(inputProps) => (
                  <input {...inputProps} type="text" defaultValue="" placeholder="e.g. HVAC Repair" style={{ width: '100%', padding: 'var(--token-space-2) var(--token-space-3)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-border-default)', fontSize: 'var(--token-fs-14)', fontFamily: 'var(--token-family-base)' }} />
                )}
              </FormField>
              <FormField label="Priority" errors={['Please select a valid priority']} fieldErrors={[{ field: 'priority', message: 'Value must be one of: critical, high, medium, low' }]}>
                {(inputProps) => (
                  <select {...inputProps} style={{ width: '100%', padding: 'var(--token-space-2) var(--token-space-3)', borderRadius: 'var(--token-radius-control)', border: '1px solid var(--token-danger-default)', fontSize: 'var(--token-fs-14)', fontFamily: 'var(--token-family-base)' }}>
                    <option value="">Select…</option>
                  </select>
                )}
              </FormField>
            </div>
          </Section>

          <Section title="ScorePresentation">
            <ScorePresentation
              score={78}
              maxScore={100}
              label="Technician Performance"
              factors={[
                { label: 'On-time completion', weight: 0.4, normalizedValue: 0.85 },
                { label: 'Customer feedback', weight: 0.3, normalizedValue: 0.72 },
                { label: 'Parts efficiency', weight: 0.2, normalizedValue: 0.91 },
                { label: 'Documentation', weight: 0.1, normalizedValue: 0.60 },
              ]}
            />
          </Section>

          <Section title="Modal">
            <Button variant="primary" onClick={() => setModalOpen(true)}>Open Modal</Button>
            <Modal open={modalOpen} onClose={() => setModalOpen(false)} title="Confirm Action">
              <p style={{ margin: 0, fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
                This is modal content. Press Escape or click outside to close.
              </p>
            </Modal>
          </Section>

          <Section title="DetailDrawer">
            <Button variant="secondary" onClick={() => setDrawerOpen(true)}>Open Drawer</Button>
            <DetailDrawer open={drawerOpen} onClose={() => setDrawerOpen(false)} title="Work Order Detail">
              <p style={{ margin: 0, fontSize: 'var(--token-fs-14)', color: 'var(--token-text-secondary)' }}>
                This is drawer content. Press Escape or click the scrim to close.
              </p>
            </DetailDrawer>
          </Section>

          <Section title="Toast">
            <ToastDemo />
          </Section>

          <Section title="Named State Components">
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 'var(--token-space-4)' }}>
              {[
                { label: 'EmptyState', node: <EmptyState /> },
                { label: 'LoadingState (first load)', node: <LoadingState /> },
                { label: 'LoadingState (refetch)', node: <LoadingState isRefetching /> },
                { label: 'DegradedState', node: <DegradedState onRetry={() => {}} /> },
                { label: 'PermissionDeniedState', node: <PermissionDeniedState /> },
                { label: 'ErrorState', node: <ErrorState onRetry={() => {}} /> },
              ].map(({ label, node }) => (
                <div key={label} style={{ border: 'var(--token-elevation-border)', borderRadius: 'var(--token-radius-card)', overflow: 'hidden' }}>
                  <div style={{ padding: 'var(--token-space-2) var(--token-space-4)', background: 'var(--token-neutral-50)', borderBottom: 'var(--token-elevation-border)', fontSize: 'var(--token-fs-12)', color: 'var(--token-text-secondary)', fontWeight: 600 }}>{label}</div>
                  {node}
                </div>
              ))}
            </div>
          </Section>
        </div>
      </DensityProvider>
    </ToastProvider>
  )
}

function Section({ title, children }) {
  return (
    <section style={{ marginBottom: 'var(--token-space-8)' }}>
      <h2 style={{ fontSize: 'var(--token-fs-20)', color: 'var(--token-text-primary)', marginBottom: 'var(--token-space-4)', paddingBottom: 'var(--token-space-3)', borderBottom: 'var(--token-elevation-border)' }}>
        {title}
      </h2>
      {children}
    </section>
  )
}
