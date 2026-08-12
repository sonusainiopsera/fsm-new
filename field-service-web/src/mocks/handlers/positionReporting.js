/**
 * MSW fixtures for WO-159: technician position reporting endpoint.
 */

export function positionAcceptedFixture() {
  return { status: 202, body: null };
}

export function positionNoActiveJobFixture() {
  return {
    status: 422,
    body: {
      code: 'NO_ACTIVE_JOB',
      message: 'Technician has no work order in EN_ROUTE or IN_PROGRESS state',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}

export function positionRateLimitedFixture() {
  return {
    status: 429,
    body: {
      code: 'POSITION_RATE_LIMITED',
      message: 'Position report rate limit exceeded; retry after 30 seconds',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
    headers: { 'Retry-After': '30' },
  };
}

export function positionValidationFailedFixture() {
  return {
    status: 400,
    body: {
      code: 'VALIDATION_FAILED',
      message: 'Position report validation failed',
      fieldErrors: [
        { field: 'capturedAt', message: 'capturedAt is older than 300 seconds' },
      ],
      traceId: 'test-trace-id',
    },
  };
}

export function positionForbiddenFixture() {
  return {
    status: 403,
    body: {
      code: 'FORBIDDEN',
      message: 'Access denied.',
      fieldErrors: [],
      traceId: 'test-trace-id',
    },
  };
}
