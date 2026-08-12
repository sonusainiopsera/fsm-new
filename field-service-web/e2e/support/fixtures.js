/**
 * @fileoverview Deterministic test fixture definitions for the technician E2E suite.
 *
 * All IDs are synthetic and anonymised — no real technician, customer or site data.
 * Fixture topology mirrors the backend V136 journey SQL fixtures.
 */

// ── Identity ─────────────────────────────────────────────────────────────────

export const TECH_1 = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000011',
  technicianId: '00000000-0000-0000-0000-000000000011',
  displayName: 'Test Technician 1',
  email: 'tech1@example.com',
  // Never a real password — test credential only
  password: 'T3ch1Pass!',
}

export const TECH_2 = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000012',
  technicianId: '00000000-0000-0000-0000-000000000012',
  displayName: 'Test Technician 2',
  email: 'tech2@example.com',
  password: 'T3ch2Pass!',
}

export const CUSTOMER_USER = {
  userId: 'aaaaaaaa-0000-0000-0000-000000000021',
  accountId: '00000000-0000-0000-0000-000000000001',
  email: 'customer@example.com',
  password: 'CustP@ss1',
}

// ── Work orders ───────────────────────────────────────────────────────────────

export const WO_JOURNEY = {
  id: 'e2000000-0000-0000-0000-000000000001',
  title: 'HVAC Inspection — Journey Test',
  priority: 'HIGH',
  site: 'Journey Test Site Alpha',
  address: '1 Journey Test Lane',
  resolutionDueAt: '2099-01-01T12:00:00Z',
}

export const WO_NO_LABOUR = {
  id: 'e2000000-0000-0000-0000-000000000002',
  title: 'Electrical Fault — No Labour Test',
  priority: 'MEDIUM',
}

export const WO_TECH2 = {
  id: 'e2000000-0000-0000-0000-000000000003',
  title: 'Cross-Tech Access Test WO',
  priority: 'LOW',
}

// ── Parts ─────────────────────────────────────────────────────────────────────

export const PART_AIR_FILTER = {
  id: '50000000-0000-0000-0000-000000000002',
  sku: 'SKU-002',
  name: 'Air Filter 20x20x1',
}

export const PART_INSUFFICIENT = {
  id: '50000000-0000-0000-0000-000000000004',
  sku: 'SKU-004',
  name: 'Contactor 24V',
}

export const TECH_1_VAN = {
  id: '60000000-0000-0000-0000-000000000011',
  name: 'Tech 1 Van A',
}

// ── Day-list mock response ────────────────────────────────────────────────────

export const DAY_LIST_FIXTURE = {
  jobs: [
    {
      id: WO_JOURNEY.id,
      title: WO_JOURNEY.title,
      priority: 'HIGH',
      state: 'ASSIGNED',
      site: WO_JOURNEY.site,
      address: WO_JOURNEY.address,
      scheduledAt: '2026-08-15T09:00:00Z',
      estimatedDurationMinutes: 90,
      partsNeeded: [PART_AIR_FILTER.name],
      slaStatus: 'ON_TRACK',
      resolutionDueAt: WO_JOURNEY.resolutionDueAt,
    },
    {
      id: WO_NO_LABOUR.id,
      title: WO_NO_LABOUR.title,
      priority: 'MEDIUM',
      state: 'IN_PROGRESS',
      site: 'Journey Test Site Beta',
      address: '2 Journey Test Avenue',
      scheduledAt: '2026-08-15T11:00:00Z',
      estimatedDurationMinutes: 60,
      partsNeeded: [],
      slaStatus: 'AT_RISK',
      resolutionDueAt: '2026-08-15T14:00:00Z',
    },
  ],
  date: '2026-08-15',
  technicianId: TECH_1.technicianId,
  totalAssigned: 2,
  generatedAt: '2026-08-15T08:00:00Z',
}

export const WO_DETAIL_ASSIGNED = {
  id: WO_JOURNEY.id,
  title: WO_JOURNEY.title,
  state: 'ASSIGNED',
  priority: 'HIGH',
  version: 0,
  assignedTechnicianId: TECH_1.technicianId,
  resolutionDueAt: WO_JOURNEY.resolutionDueAt,
  legalEvents: ['DEPART', 'START', 'HOLD', 'CANCEL'],
}
