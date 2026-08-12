/**
 * MSW fixtures for the log-work screen.
 *
 * Provides fixtures for:
 * - Vehicle stock lines (zero, one, many)
 * - Labour time success (200) and idempotent replay
 * - Parts consumption success (200)
 * - Parts consumption 422 INSUFFICIENT_STOCK with shortfall detail
 * - COMPLETE transition success (200)
 * - COMPLETE transition 422 guard failure (missing labour time)
 * - Offline / 503 retry
 */

/** Three vehicle stock lines for normal testing. */
export function vehicleStockFixture() {
  return {
    status: 200,
    body: {
      data: [
        {
          partId:        'part-001',
          partCode:      'COMP-4470',
          partName:      'Compressor relay',
          description:   'Single-pole relay for compressor start circuit',
          unitOfMeasure: 'EA',
          quantityOnHand: 3,
          locationId:    'loc-van-001',
        },
        {
          partId:        'part-002',
          partCode:      'CAP-220UF',
          partName:      'Start capacitor 220µF',
          description:   null,
          unitOfMeasure: 'EA',
          quantityOnHand: 2,
          locationId:    'loc-van-001',
        },
        {
          partId:        'part-003',
          partCode:      'FILT-G4',
          partName:      'G4 panel filter',
          description:   null,
          unitOfMeasure: 'EA',
          quantityOnHand: 5,
          locationId:    'loc-van-001',
        },
      ],
      page: { number: 0, size: 50, totalElements: 3, totalPages: 1, estimated: null },
      _links: { next: null, prev: null },
    },
  };
}

/** Empty vehicle stock — zero parts on van. */
export function vehicleStockEmptyFixture() {
  return {
    status: 200,
    body: {
      data: [],
      page: { number: 0, size: 50, totalElements: 0, totalPages: 0, estimated: null },
      _links: { next: null, prev: null },
    },
  };
}

/** Successful labour time log. */
export function labourSuccessFixture() {
  return {
    status: 200,
    body: {
      entryId:     'labour-001',
      workOrderId: 'tech-wo-001',
      minutes:     90,
    },
  };
}

/** Successful parts consumption. */
export function partsConsumedFixture() {
  return {
    status: 200,
    body: {
      workOrderId: 'tech-wo-001',
      lines: [
        { partId: 'part-001', quantity: 1, partCode: 'COMP-4470' },
      ],
    },
  };
}

/**
 * 422 INSUFFICIENT_STOCK — requesting 3 of COMP-4470 when only 1 remains.
 * fieldErrors index matches the lines[] array index in the request.
 */
export function partsShortfallFixture() {
  return {
    status: 422,
    body: {
      status:  422,
      code:    'INSUFFICIENT_STOCK',
      message: 'Insufficient stock for 1 line(s)',
      fieldErrors: [
        {
          field:   'lines[0].quantity',
          message: 'requested 3, available 1',
        },
      ],
      traceId: 'test-shortfall-001',
    },
  };
}

/**
 * 422 GUARD_FAILED on COMPLETE — labour time not yet recorded.
 */
export function completeGuardFixture() {
  return {
    status: 422,
    body: {
      status:  422,
      code:    'GUARD_FAILED',
      message: 'At least one labour time entry must be recorded before completing this work order.',
      fieldErrors: [
        {
          field:   'guard',
          message: 'At least one labour time entry must be recorded before completing this work order.',
        },
      ],
      traceId: 'test-guard-001',
    },
  };
}

/** 503 Service unavailable — retry affordance. */
export function serviceUnavailableFixture() {
  return {
    status: 503,
    body: {
      status:  503,
      code:    'SERVICE_UNAVAILABLE',
      message: 'Service temporarily unavailable. Please try again.',
      fieldErrors: [],
    },
  };
}
