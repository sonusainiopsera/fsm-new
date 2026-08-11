import { describe, it, expect } from 'vitest';
import {
  countTokenDeclarations,
  countTotalDeclarations,
  computeAdoptionPct,
  findHardcodedLiterals,
  aggregateMetrics,
} from '../adoptionMetrics.js';

const ALL_TOKEN_CSS = `
.card {
  color: var(--color-text-primary);
  background-color: var(--color-surface-base);
  padding: var(--space-4);
  border-radius: var(--radius-card);
}
`;

const MIXED_CSS = `
.card {
  color: var(--color-text-primary);
  background-color: #ffffff;
  padding: var(--space-4);
}
`;

const LITERAL_CSS = `
.button {
  color: #ff0000;
  background-color: rgb(255, 0, 0);
  padding: var(--space-2);
}
`;

describe('countTotalDeclarations', () => {
  it('counts visual property declarations', () => {
    expect(countTotalDeclarations(ALL_TOKEN_CSS)).toBe(4);
  });

  it('returns 0 for empty CSS', () => {
    expect(countTotalDeclarations('')).toBe(0);
  });
});

describe('countTokenDeclarations', () => {
  it('counts all declarations when all use tokens', () => {
    expect(countTokenDeclarations(ALL_TOKEN_CSS)).toBe(4);
  });

  it('counts only token-using declarations in mixed CSS', () => {
    expect(countTokenDeclarations(MIXED_CSS)).toBe(2);
  });
});

describe('computeAdoptionPct', () => {
  it('returns 100 for fully tokenised CSS', () => {
    expect(computeAdoptionPct(ALL_TOKEN_CSS)).toBe(100);
  });

  it('returns correct percentage for mixed CSS (2 of 3 ≈ 66.7%)', () => {
    // MIXED_CSS has 3 visual declarations: color (token), background-color (literal), padding (token)
    expect(computeAdoptionPct(MIXED_CSS)).toBeGreaterThan(60);
    expect(computeAdoptionPct(MIXED_CSS)).toBeLessThan(70);
  });

  it('returns 100 for empty CSS (no declarations)', () => {
    expect(computeAdoptionPct('')).toBe(100);
  });
});

describe('findHardcodedLiterals', () => {
  it('finds hex colour literals', () => {
    const results = findHardcodedLiterals(LITERAL_CSS);
    expect(results.some((r) => r.value === '#ff0000')).toBe(true);
  });

  it('finds rgb() colour literals', () => {
    const results = findHardcodedLiterals(LITERAL_CSS);
    expect(results.some((r) => r.value.startsWith('rgb('))).toBe(true);
  });

  it('does not flag var() references as literals', () => {
    const results = findHardcodedLiterals(ALL_TOKEN_CSS);
    expect(results).toHaveLength(0);
  });

  it('exempts values in the allow-list', () => {
    const results = findHardcodedLiterals(LITERAL_CSS, ['#ff0000']);
    expect(results.some((r) => r.value === '#ff0000')).toBe(false);
  });

  it('returns line numbers for each literal found', () => {
    const results = findHardcodedLiterals(LITERAL_CSS);
    expect(results.every((r) => typeof r.line === 'number' && r.line > 0)).toBe(true);
  });
});

describe('aggregateMetrics', () => {
  it('computes adoption percentage across multiple file metrics', () => {
    const files = [
      { total: 10, tokenised: 10, literals: 0 },
      { total: 10, tokenised: 8, literals: 1 },
    ];
    const { adoptionPct, bespokePct, totalLiterals } = aggregateMetrics(files);
    expect(adoptionPct).toBe(90);
    expect(bespokePct).toBe(10);
    expect(totalLiterals).toBe(1);
  });

  it('returns 100% adoption for all-token files', () => {
    const files = [{ total: 5, tokenised: 5, literals: 0 }];
    const { adoptionPct } = aggregateMetrics(files);
    expect(adoptionPct).toBe(100);
  });

  it('fails the gate at below 95% adoption', () => {
    const files = [{ total: 20, tokenised: 15, literals: 0 }];
    const { adoptionPct } = aggregateMetrics(files);
    expect(adoptionPct).toBeLessThan(95);
  });

  it('returns 100% and 0 literals for empty file list', () => {
    const { adoptionPct, totalLiterals } = aggregateMetrics([]);
    expect(adoptionPct).toBe(100);
    expect(totalLiterals).toBe(0);
  });
});
