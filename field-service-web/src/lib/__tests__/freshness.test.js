import { describe, it, expect } from 'vitest';
import { evaluateFreshness, staleDescription, STALENESS_BUDGET_MS } from '../freshness.js';

describe('evaluateFreshness', () => {
  const now = 1000000000000; // fixed epoch ms

  it('returns isFresh=true when age < 60s', () => {
    const asOf = new Date(now - 30_000).toISOString();
    const result = evaluateFreshness(asOf, now);
    expect(result.isFresh).toBe(true);
    expect(result.ageSeconds).toBe(30);
  });

  it('returns isFresh=true at exactly 60s boundary', () => {
    const asOf = new Date(now - STALENESS_BUDGET_MS).toISOString();
    const result = evaluateFreshness(asOf, now);
    expect(result.isFresh).toBe(true);
  });

  it('returns isFresh=false when age > 60s', () => {
    const asOf = new Date(now - 61_000).toISOString();
    const result = evaluateFreshness(asOf, now);
    expect(result.isFresh).toBe(false);
    expect(result.ageSeconds).toBe(61);
  });

  it('returns isFresh=false for null asOf', () => {
    const result = evaluateFreshness(null, now);
    expect(result.isFresh).toBe(false);
  });

  it('returns isFresh=false for invalid asOf string', () => {
    const result = evaluateFreshness('not-a-date', now);
    expect(result.isFresh).toBe(false);
  });
});

describe('staleDescription', () => {
  it('uses seconds when age < 120s', () => {
    const desc = staleDescription('Stock positions', 90);
    expect(desc).toContain('90 seconds old');
    expect(desc).toContain('Stock positions');
  });

  it('uses minutes when age >= 120s', () => {
    const desc = staleDescription('Low-stock alerts', 180);
    expect(desc).toContain('3 minutes old');
    expect(desc).toContain('Low-stock alerts');
  });

  it('uses singular minute for exactly 60s', () => {
    const desc = staleDescription('Stock', 60);
    expect(desc).toMatch(/1 minute old/);
  });

  it('handles Infinity age gracefully', () => {
    const desc = staleDescription('Movements', Infinity);
    expect(desc).toContain('freshness is unknown');
  });
});
