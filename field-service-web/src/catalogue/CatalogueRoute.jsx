import React, { useState } from 'react';

import { DensityProvider } from '../density/DensityContext.js';
import {
  Button,
  PageHeader,
  KpiCard,
  DataTable,
  DetailDrawer,
  Modal,
  FormField,
  Chip,
  ScorePresentation,
  ToastProvider,
  useToast,
  EmptyState,
  LoadingState,
  DegradedState,
  PermissionDeniedState,
  ErrorState,
} from '../components/index.js';

import dispatcherFixture from '../mocks/fixtures/dispatcher.json';
import managerFixture from '../mocks/fixtures/manager.json';

import styles from './CatalogueRoute.module.css';

function Section({ title, children }) {
  return (
    <section className={styles.section}>
      <h2 className={styles.sectionTitle}>{title}</h2>
      <div className={styles.sectionContent}>{children}</div>
    </section>
  );
}

function ToastDemo() {
  const { show } = useToast();
  return (
    <div className={styles.row}>
      {['info', 'success', 'warning', 'danger'].map((v) => (
        <Button
          key={v}
          variant="secondary"
          onClick={() => show({ variant: v, message: `${v} toast`, detail: 'Additional detail here' })}
        >
          Show {v}
        </Button>
      ))}
    </div>
  );
}

const WO_COLUMNS = [
  { key: 'reference', header: 'Reference', sortable: true },
  { key: 'customer', header: 'Customer' },
  { key: 'priority', header: 'Priority', render: (v) => <Chip variant="priority" value={v} /> },
  { key: 'state', header: 'State', render: (v) => <Chip variant="state" value={v} /> },
  { key: 'score', header: 'Score', numeric: true },
];

export default function CatalogueRoute() {
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [modalOpen, setModalOpen] = useState(false);
  const [selectedWo, setSelectedWo] = useState(null);
  const [formErrors, setFormErrors] = useState([]);

  return (
    <ToastProvider>
      <DensityProvider>
        <div className={styles.page}>
          <PageHeader
            title="Component Catalogue"
            breadcrumb={[{ label: 'Home', href: '/' }, { label: 'Catalogue' }]}
            primaryAction={<Button onClick={() => setModalOpen(true)}>Open Modal</Button>}
            secondaryActions={[
              <Button key="drawer" variant="secondary" onClick={() => setDrawerOpen(true)}>Open Drawer</Button>,
            ]}
          />

          <div className={styles.content}>

            <Section title="Buttons">
              <div className={styles.row}>
                {['primary', 'secondary', 'tertiary', 'ghost', 'destructive'].map((v) => (
                  <Button key={v} variant={v}>{v}</Button>
                ))}
                <Button loading>Loading</Button>
                <Button disabled>Disabled</Button>
                <Button touch>Touch (44px)</Button>
              </div>
            </Section>

            <Section title="Chips — Priority">
              <div className={styles.row}>
                {['urgent', 'high', 'normal', 'low'].map((v) => (
                  <Chip key={v} variant="priority" value={v} />
                ))}
                <Chip variant="priority" value="unknown-value" />
              </div>
            </Section>

            <Section title="Chips — State">
              <div className={styles.row}>
                {['new', 'assigned', 'en_route', 'in_progress', 'on_hold', 'completed', 'closed', 'cancelled'].map((v) => (
                  <Chip key={v} variant="state" value={v} />
                ))}
              </div>
            </Section>

            <Section title="Chips — Risk">
              <div className={styles.row}>
                {['high', 'medium', 'low'].map((v) => (
                  <Chip key={v} variant="risk" value={v} />
                ))}
              </div>
            </Section>

            <Section title="KPI Cards">
              <div className={styles.kpiGrid}>
                {managerFixture.kpis.map((kpi) => (
                  <KpiCard
                    key={kpi.key}
                    label={kpi.label}
                    value={kpi.value}
                    delta={kpi.delta}
                    deltaLabel={kpi.deltaLabel}
                    target={kpi.target}
                    current={kpi.current}
                    sparklineData={kpi.sparklineData}
                  />
                ))}
                <KpiCard label="No delta or target" value={99} />
              </div>
            </Section>

            <Section title="Score Presentation">
              <ScorePresentation
                score={87}
                maxScore={100}
                label="Technician Match Score"
                factors={[
                  { label: 'Certification match', weight: 0.35, normalizedValue: 0.96 },
                  { label: 'Proximity', weight: 0.30, normalizedValue: 0.82 },
                  { label: 'Availability', weight: 0.20, normalizedValue: 0.75 },
                  { label: 'Workload balance', weight: 0.15, normalizedValue: 0.68 },
                ]}
              />
            </Section>

            <Section title="Data Table">
              <DataTable
                columns={WO_COLUMNS}
                data={dispatcherFixture.workOrders}
                rowKey={(r) => r.id}
                caption="Work orders"
                selectedKey={selectedWo}
                onRowClick={(r) => setSelectedWo(r.id === selectedWo ? null : r.id)}
                emptyState={<EmptyState description="No work orders found" />}
              />
            </Section>

            <Section title="Data Table — Empty">
              <DataTable
                columns={WO_COLUMNS}
                data={[]}
                caption="Empty work orders"
                emptyState={<EmptyState description="No work orders match your filters" />}
              />
            </Section>

            <Section title="Form Field">
              <div className={styles.formStack}>
                <FormField label="Customer name" required help="Enter the customer account name">
                  <input type="text" placeholder="Acme Corp" />
                </FormField>
                <FormField label="Priority" errors={[{ field: 'priority', message: 'Priority is required' }]}>
                  <select>
                    <option value="">Select priority</option>
                    <option value="urgent">Urgent</option>
                    <option value="high">High</option>
                  </select>
                </FormField>
                <FormField
                  label="Description"
                  errors={['Description must be at least 20 characters', 'Description contains restricted content']}
                >
                  <textarea rows={3} placeholder="Describe the fault…" />
                </FormField>
              </div>
            </Section>

            <Section title="Named States">
              <div className={styles.statesGrid}>
                <div className={styles.stateCard}><EmptyState /></div>
                <div className={styles.stateCard}><LoadingState /></div>
                <div className={styles.stateCard}><DegradedState /></div>
                <div className={styles.stateCard}><PermissionDeniedState /></div>
                <div className={styles.stateCard}><ErrorState onRetry={() => {}} /></div>
                <div className={styles.stateCard}><LoadingState isRefetch /></div>
              </div>
            </Section>

            <Section title="Toast Notifications">
              <ToastDemo />
            </Section>

          </div>

          <DetailDrawer
            open={drawerOpen}
            title="Work Order Detail"
            onClose={() => setDrawerOpen(false)}
            footer={
              <>
                <Button variant="secondary" onClick={() => setDrawerOpen(false)}>Cancel</Button>
                <Button>Assign</Button>
              </>
            }
          >
            <p>Drawer body content. Full work order details would appear here.</p>
          </DetailDrawer>

          <Modal
            open={modalOpen}
            title="Create Work Order"
            onClose={() => setModalOpen(false)}
            footer={
              <>
                <Button variant="ghost" onClick={() => setModalOpen(false)}>Cancel</Button>
                <Button onClick={() => { setFormErrors(['Please fill in all required fields']); }}>
                  Create
                </Button>
              </>
            }
          >
            <FormField label="Customer" required errors={formErrors}>
              <input type="text" placeholder="Customer name" />
            </FormField>
          </Modal>
        </div>
      </DensityProvider>
    </ToastProvider>
  );
}
