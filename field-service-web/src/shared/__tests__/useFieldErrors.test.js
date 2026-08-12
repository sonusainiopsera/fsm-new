/**
 * Tests for useFieldErrors and mapFieldErrors.
 */

import { describe, it, expect } from 'vitest';
import { mapFieldErrors } from '../forms/useFieldErrors.js';
import { ClientError } from '../../api/errors.js';

function makeError(status, fieldErrors, message = 'Error') {
  return new ClientError(status, 'VALIDATION_FAILED', message, fieldErrors, 'trace-123');
}

describe('mapFieldErrors', () => {
  it('returns empty state when error is null', () => {
    const s = mapFieldErrors(null);
    expect(s.hasErrors).toBe(false);
    expect(s.summaryErrors).toEqual([]);
    expect(s.errorsFor('name')).toEqual([]);
    expect(s.traceId).toBeNull();
  });

  it('maps field errors to known fields', () => {
    const err = makeError(400, [
      { field: 'name', message: 'must not be blank' },
      { field: 'contactEmail', message: 'invalid email' },
    ]);
    const s = mapFieldErrors(err, ['name', 'contactEmail', 'phone']);
    expect(s.errorsFor('name')).toEqual(['must not be blank']);
    expect(s.errorsFor('contactEmail')).toEqual(['invalid email']);
    expect(s.errorsFor('phone')).toEqual([]);
    expect(s.summaryErrors).toEqual([]);
    expect(s.hasErrors).toBe(true);
    expect(s.traceId).toBe('trace-123');
  });

  it('routes unknown fields to summary region', () => {
    const err = makeError(400, [
      { field: 'unknownServerField', message: 'some constraint failed' },
    ]);
    const s = mapFieldErrors(err, ['name', 'email']);
    expect(s.errorsFor('name')).toEqual([]);
    expect(s.summaryErrors).toEqual(['some constraint failed']);
    expect(s.hasErrors).toBe(true);
  });

  it('collects multiple errors for the same field', () => {
    const err = makeError(400, [
      { field: 'code', message: 'too short' },
      { field: 'code', message: 'invalid characters' },
    ]);
    const s = mapFieldErrors(err, ['code']);
    expect(s.errorsFor('code')).toEqual(['too short', 'invalid characters']);
  });

  it('puts top-level message in summary when no fieldErrors', () => {
    const err = makeError(422, [], 'Certification is not current');
    const s = mapFieldErrors(err, ['technicianId']);
    expect(s.summaryErrors).toEqual(['Certification is not current']);
    expect(s.hasErrors).toBe(true);
  });

  it('ignores fieldErrors with missing field property', () => {
    const err = makeError(400, [{ message: 'bad' }, null, undefined]);
    const s = mapFieldErrors(err, ['name']);
    expect(s.hasErrors).toBe(false);
  });

  it('accepts all fields when knownFields is empty', () => {
    const err = makeError(400, [
      { field: 'anything', message: 'any error' },
    ]);
    const s = mapFieldErrors(err, []);
    expect(s.errorsFor('anything')).toEqual(['any error']);
    expect(s.summaryErrors).toEqual([]);
  });
});
