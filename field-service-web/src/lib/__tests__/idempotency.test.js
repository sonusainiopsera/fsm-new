import { describe, it, expect } from 'vitest';
import { newAttemptKey } from '../idempotency.js';

describe('newAttemptKey', () => {
  it('returns a UUID string', () => {
    const key = newAttemptKey();
    expect(typeof key).toBe('string');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i);
  });

  it('generates unique keys on each call', () => {
    const k1 = newAttemptKey();
    const k2 = newAttemptKey();
    expect(k1).not.toBe(k2);
  });
});
