/**
 * MSW fixtures for the assignment and reassignment endpoints.
 *
 * POST /api/v1/work-orders/{workOrderId}/assignment
 * POST /api/v1/work-orders/{workOrderId}/reassignment
 *
 * Exports:
 *   WO_ASSIGN_ID              — stable work-order ID for assignment tests
 *   assignSuccessFixture()
 *   reassignSuccessFixture()
 *   assignCertRefusedFixture()
 *   assignAppointmentBreachFixture()
 *   assignConflictFixture()
 *   assignFieldErrorsFixture()
 *   assignRateLimitFixture()
 *   assignServerErrorFixture()
 */

export const WO_ASSIGN_ID = 'wo-assign-001';

const NOW = '2026-08-12T10:30:00Z';
const ASSIGNMENT_ID = '00000000-0000-7141-0000-000000000001';
const TECHNICIAN_ID = 'tech-rec-001';
const TECHNICIAN_NAME = 'Technician 1';

/** 200 OK — successful first assignment. */
export function assignSuccessFixture(overrides = {}) {
  return {
    status: 200,
    body: {
      data: {
        assignmentId: ASSIGNMENT_ID,
        workOrderId: WO_ASSIGN_ID,
        technicianId: TECHNICIAN_ID,
        state: 'ASSIGNED',
        assignedAt: NOW,
        recommendationRank: 1,
        recommendationScore: 0.95,
        overrideRecorded: false,
        partsWarning: null,
        ...overrides,
      },
      meta: { traceId: 'trace-assign-001' },
    },
  };
}

/** 200 OK — successful reassignment. */
export function reassignSuccessFixture(overrides = {}) {
  return {
    status: 200,
    body: {
      data: {
        assignmentId: '00000000-0000-7141-0000-000000000002',
        supersededAssignmentId: '00000000-0000-7141-0000-000000000000',
        workOrderId: WO_ASSIGN_ID,
        technicianId: TECHNICIAN_ID,
        state: 'ASSIGNED',
        reassignmentReason: 'SLA_RISK',
        appointmentImpactRecorded: false,
        assignedAt: NOW,
        ...overrides,
      },
      meta: { traceId: 'trace-reassign-001' },
    },
  };
}

/** 422 — certification guard refused (terminal, non-overridable). */
export function assignCertRefusedFixture() {
  return {
    status: 422,
    body: {
      status: 422,
      code: 'CERTIFICATION_GUARD_REFUSED',
      message: 'Technician does not hold required GAS_SAFE certification.',
      fieldErrors: [],
      traceId: 'trace-cert-422',
    },
  };
}

/** 422 — confirmed appointment breach (ack step required). */
export function assignAppointmentBreachFixture() {
  return {
    status: 422,
    body: {
      status: 422,
      code: 'CONFIRMED_APPOINTMENT_BREACH',
      message: 'This work order has a confirmed customer appointment. Provide an acknowledgement.',
      fieldErrors: [],
      traceId: 'trace-appt-422',
    },
  };
}

/** 200 OK — successful resubmit after appointment ack. */
export function assignAfterAckFixture() {
  return assignSuccessFixture({ appointmentImpactRecorded: true });
}

/** 409 — conflict (illegal transition or optimistic lock). */
export function assignConflictFixture() {
  return {
    status: 409,
    body: {
      status: 409,
      code: 'WORK_ORDER_ILLEGAL_TRANSITION',
      message: 'Work order is already assigned to another technician.',
      fieldErrors: [],
      traceId: 'trace-conflict-409',
    },
  };
}

/** 400 — field validation errors. */
export function assignFieldErrorsFixture() {
  return {
    status: 400,
    body: {
      status: 400,
      code: 'VALIDATION_FAILED',
      message: 'The request contained invalid data.',
      fieldErrors: [
        { field: 'overrideReason', message: 'Override reason must not be blank.' },
        { field: 'reassignmentReason', message: 'Reassignment reason is required.' },
      ],
      traceId: 'trace-field-400',
    },
  };
}

/** 429 — rate limited. */
export function assignRateLimitFixture(retryAfterSeconds = 30) {
  return {
    status: 429,
    body: {
      status: 429,
      code: 'RATE_LIMITED',
      message: 'Too many assignment requests. Please wait before retrying.',
      fieldErrors: [],
      traceId: 'trace-rate-429',
    },
    headers: { 'Retry-After': String(retryAfterSeconds) },
  };
}

/** 500 — server error (retryable). */
export function assignServerErrorFixture() {
  return {
    status: 500,
    body: {
      status: 500,
      code: 'INTERNAL_ERROR',
      message: 'An unexpected error occurred.',
      fieldErrors: [],
      traceId: 'trace-server-500',
    },
  };
}

/** 200 OK — success with a parts warning. */
export function assignWithPartsWarningFixture() {
  return assignSuccessFixture({
    partsWarning: {
      message: 'Part HVA-0055 may be unavailable on this vehicle.',
      partNumber: 'HVA-0055',
    },
  });
}

/** Fixture showing a rank-4 candidate (requires override reason). */
export function rank4CandidateFixture() {
  return {
    technicianId: 'tech-rec-004',
    technicianName: 'Technician 4',
    rank: 4,
    score: 0.72,
    travelEstimateDegraded: false,
    factors: [],
  };
}

/** Fixture showing a rank-3 candidate (does NOT require override reason). */
export function rank3CandidateFixture() {
  return {
    technicianId: 'tech-rec-003',
    technicianName: 'Technician 3',
    rank: 3,
    score: 0.80,
    travelEstimateDegraded: false,
    factors: [],
  };
}
