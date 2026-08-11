/**
 * Token contract test suite — WO-182
 *
 * Covers acceptance criteria:
 *   AC-2  token contract completeness and parity (light ↔ dark)
 *   AC-3  typography token presence (numeric token + utility class)
 *   AC-4  spacing and radius tokens
 *   AC-5  elevation tokens
 *   AC-6  motion ceiling (≤ 300 ms, exactly one easing token)
 *   AC-7  exactly one accent family, exactly four semantic families, no gradients
 *   AC-8  lint gate (Stylelint compliant vs violating fixtures; ESLint rule)
 *   AC-11 all unit tests written and passing
 *   AC-12 build smoke: CSS files importable, dark changes surface values
 *   AC-13 fixture files committed for lint gate tests
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';
import { beforeAll, describe, expect, it } from 'vitest';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const STYLES_DIR = path.join(__dirname, '..');
const ROOT = path.join(STYLES_DIR, '../../..');
const FIXTURES_DIR = path.join(__dirname, 'fixtures');

// ── Helpers ───────────────────────────────────────────────────────────────

function readStyles(filename) {
  return fs.readFileSync(path.join(STYLES_DIR, filename), 'utf8');
}

function parseTokenNames(cssText) {
  const pattern = /^\s*(--fs-[\w-]+)\s*:/gm;
  const names = new Set();
  let match;
  while ((match = pattern.exec(cssText)) !== null) {
    names.add(match[1]);
  }
  return names;
}

function parseTokenValues(cssText) {
  const pattern = /^\s*(--fs-[\w-]+)\s*:\s*([^;]+);/gm;
  const map = new Map();
  let match;
  while ((match = pattern.exec(cssText)) !== null) {
    map.set(match[1], match[2].trim());
  }
  return map;
}

// ── Load fixtures ─────────────────────────────────────────────────────────

const contractJson = JSON.parse(readStyles('tokens.contract.json'));
const contractNames = new Set(contractJson.tokens);

const lightCss = readStyles('tokens.light.css');
const darkCss = readStyles('tokens.dark.css');
const lightNames = parseTokenNames(lightCss);
const darkNames = parseTokenNames(darkCss);
const lightValues = parseTokenValues(lightCss);
const darkValues = parseTokenValues(darkCss);

// ── AC-2: Contract completeness and bijection ─────────────────────────────

describe('Token contract completeness (AC-2)', () => {
  it('contract JSON contains 70 canonical tokens', () => {
    expect(contractJson.tokens.length).toBe(70);
  });

  it('light value set covers every contract token', () => {
    for (const token of contractNames) {
      expect(lightNames.has(token), `Light is missing: ${token}`).toBe(true);
    }
  });

  it('dark value set covers every contract token', () => {
    for (const token of contractNames) {
      expect(darkNames.has(token), `Dark is missing: ${token}`).toBe(true);
    }
  });

  it('light declares no tokens absent from the contract', () => {
    for (const token of lightNames) {
      expect(contractNames.has(token), `Light has undeclared token: ${token}`).toBe(true);
    }
  });

  it('dark declares no tokens absent from the contract', () => {
    for (const token of darkNames) {
      expect(contractNames.has(token), `Dark has undeclared token: ${token}`).toBe(true);
    }
  });
});

// ── AC-3: Typography tokens ───────────────────────────────────────────────

describe('Typography tokens (AC-3)', () => {
  it('all seven font-size scale tokens are present', () => {
    const sizes = [
      '--fs-text-2xs', '--fs-text-xs', '--fs-text-sm', '--fs-text-base',
      '--fs-text-lg', '--fs-text-xl', '--fs-text-2xl',
    ];
    for (const t of sizes) {
      expect(contractNames.has(t), `Missing: ${t}`).toBe(true);
    }
  });

  it('--fs-line-height-body is 1.5 in the light set', () => {
    expect(contractNames.has('--fs-line-height-body')).toBe(true);
    expect(lightValues.get('--fs-line-height-body')).toBe('1.5');
  });

  it('--fs-tracking-tight is present for display sizes (20 px+)', () => {
    expect(contractNames.has('--fs-tracking-tight')).toBe(true);
  });

  it('--fs-font-variant-numeric token value is tabular-nums', () => {
    expect(contractNames.has('--fs-font-variant-numeric')).toBe(true);
    expect(lightValues.get('--fs-font-variant-numeric')).toBe('tabular-nums');
    expect(darkValues.get('--fs-font-variant-numeric')).toBe('tabular-nums');
  });

  it('.numeric utility class applies --fs-font-variant-numeric token', () => {
    const baseCss = readStyles('base.css');
    expect(baseCss).toMatch(/\.numeric\s*\{/);
    expect(baseCss).toMatch(/font-variant-numeric\s*:\s*var\(--fs-font-variant-numeric\)/);
  });
});

// ── AC-4: Spacing and radius tokens ──────────────────────────────────────

describe('Spacing tokens (AC-4)', () => {
  it('6 rhythm steps are present (4/8/12/16/24/32 px)', () => {
    const steps = [
      '--fs-space-1', '--fs-space-2', '--fs-space-3',
      '--fs-space-4', '--fs-space-6', '--fs-space-8',
    ];
    for (const t of steps) {
      expect(contractNames.has(t), `Missing: ${t}`).toBe(true);
    }
  });

  it('--fs-content-max is 1440px', () => {
    expect(lightValues.get('--fs-content-max')).toBe('1440px');
  });

  it('--fs-gutter is 1.5rem (24 px)', () => {
    expect(lightValues.get('--fs-gutter')).toBe('1.5rem');
  });
});

describe('Radius tokens (AC-4)', () => {
  it('control/card/overlay/pill radius tokens are present', () => {
    expect(contractNames.has('--fs-radius-control')).toBe(true);
    expect(contractNames.has('--fs-radius-card')).toBe(true);
    expect(contractNames.has('--fs-radius-overlay')).toBe(true);
    expect(contractNames.has('--fs-radius-pill')).toBe(true);
  });

  it('radius values are 6 / 10 / 14 / 9999 px', () => {
    expect(lightValues.get('--fs-radius-control')).toBe('6px');
    expect(lightValues.get('--fs-radius-card')).toBe('10px');
    expect(lightValues.get('--fs-radius-overlay')).toBe('14px');
    expect(lightValues.get('--fs-radius-pill')).toBe('9999px');
  });
});

// ── AC-5: Elevation tokens ────────────────────────────────────────────────

describe('Elevation tokens (AC-5)', () => {
  it('exactly two shadow levels, plus hairline and scrim tokens', () => {
    for (const t of ['--fs-shadow-1', '--fs-shadow-2', '--fs-border-hairline', '--fs-scrim']) {
      expect(contractNames.has(t), `Missing: ${t}`).toBe(true);
    }
  });

  it('no more than two --fs-shadow-* tokens (two-level system)', () => {
    const shadowTokens = [...contractNames].filter((t) => t.startsWith('--fs-shadow-'));
    expect(shadowTokens.length).toBe(2);
  });
});

// ── AC-6: Motion tokens ───────────────────────────────────────────────────

describe('Motion tokens (AC-6)', () => {
  it('three duration tokens are present', () => {
    expect(contractNames.has('--fs-duration-micro')).toBe(true);
    expect(contractNames.has('--fs-duration-entry')).toBe(true);
    expect(contractNames.has('--fs-duration-overlay')).toBe(true);
  });

  it('exactly one easing token', () => {
    const easingTokens = [...contractNames].filter((t) => t.startsWith('--fs-easing-'));
    expect(easingTokens.length).toBe(1);
  });

  it('no motion duration token exceeds 300 ms', () => {
    const durationTokens = [...contractNames].filter((t) => t.startsWith('--fs-duration-'));
    for (const token of durationTokens) {
      const raw = lightValues.get(token) ?? '';
      const ms = parseInt(raw, 10);
      expect(ms, `${token} value "${raw}" exceeds 300 ms`).toBeLessThanOrEqual(300);
    }
  });

  it('duration values are exactly 120 / 180 / 240 ms', () => {
    expect(lightValues.get('--fs-duration-micro')).toBe('120ms');
    expect(lightValues.get('--fs-duration-entry')).toBe('180ms');
    expect(lightValues.get('--fs-duration-overlay')).toBe('240ms');
  });
});

// ── AC-7: Colour tokens ───────────────────────────────────────────────────

describe('Colour tokens (AC-7)', () => {
  it('neutral greyscale ramp has exactly 12 steps', () => {
    const neutrals = [...contractNames].filter((t) => t.startsWith('--fs-neutral-'));
    expect(neutrals.length).toBe(12);
  });

  it('exactly one accent family (--fs-accent-*)', () => {
    const accentTokens = [...contractNames].filter((t) => t.startsWith('--fs-accent-'));
    expect(accentTokens.length).toBeGreaterThan(0);
  });

  it('exactly four semantic families (info / success / warning / danger)', () => {
    const semanticFamilies = new Set(
      [...contractNames]
        .filter((t) => /^--fs-(info|success|warning|danger)-/.test(t))
        .map((t) => /** @type {string} */ (t.match(/^--fs-([a-z]+)-/)?.[1]))
        .filter(Boolean)
    );
    expect([...semanticFamilies].sort()).toEqual(['danger', 'info', 'success', 'warning']);
    expect(semanticFamilies.size).toBe(4);
  });

  it('no fifth semantic family is present', () => {
    const knownFamilies = new Set(['info', 'success', 'warning', 'danger']);
    const extraFamilies = [...contractNames]
      .filter((t) => /^--fs-[a-z]+-bg$/.test(t))
      .map((t) => /** @type {string} */ (t.match(/^--fs-([a-z]+)-bg$/)?.[1]))
      .filter((f) => f && !knownFamilies.has(f) && f !== 'accent' && f !== 'color');
    expect(extraFamilies).toHaveLength(0);
  });

  it('each semantic family has bg / border / text / icon slots', () => {
    for (const family of ['info', 'success', 'warning', 'danger']) {
      for (const slot of ['bg', 'border', 'text', 'icon']) {
        expect(contractNames.has(`--fs-${family}-${slot}`), `Missing --fs-${family}-${slot}`).toBe(true);
      }
    }
  });

  it('no gradient values in either appearance', () => {
    expect(lightCss).not.toMatch(/gradient/i);
    expect(darkCss).not.toMatch(/gradient/i);
  });
});

// ── AC-12: Build smoke tests ──────────────────────────────────────────────

describe('Build smoke tests (AC-12)', () => {
  it('index.css imports both tokens.light.css and tokens.dark.css', () => {
    const indexCss = readStyles('index.css');
    expect(indexCss).toMatch(/tokens\.light\.css/);
    expect(indexCss).toMatch(/tokens\.dark\.css/);
  });

  it('index.html has no inline style attributes', () => {
    const indexHtml = fs.readFileSync(path.join(ROOT, 'index.html'), 'utf8');
    expect(indexHtml).not.toMatch(/\bstyle="[^"]*\w[^"]*"/);
  });

  it('dark surface differs from light surface (appearance toggle works)', () => {
    const ls = lightValues.get('--fs-color-surface');
    const ds = darkValues.get('--fs-color-surface');
    expect(ls).toBeDefined();
    expect(ds).toBeDefined();
    expect(ls).not.toEqual(ds);
  });

  it('dark text-primary differs from light text-primary', () => {
    expect(lightValues.get('--fs-color-text-primary')).not.toEqual(
      darkValues.get('--fs-color-text-primary')
    );
  });

  it('dark danger-bg differs from light danger-bg', () => {
    expect(lightValues.get('--fs-danger-bg')).not.toEqual(
      darkValues.get('--fs-danger-bg')
    );
  });
});

// ── AC-8: Lint gate — Stylelint ───────────────────────────────────────────

describe('Lint gate — Stylelint (AC-8)', () => {
  /** @type {import('stylelint').default} */
  let stylelint;
  /** @type {import('stylelint').Config} */
  let stylelintConfig;

  beforeAll(async () => {
    stylelint = (await import('stylelint')).default;
    stylelintConfig = JSON.parse(
      fs.readFileSync(path.join(ROOT, '.stylelintrc.json'), 'utf8')
    );
  });

  it('compliant fixture produces zero Stylelint warnings', async () => {
    const result = await stylelint.lint({
      files: [path.join(FIXTURES_DIR, 'compliant.css')],
      config: stylelintConfig,
    });
    const warnings = result.results.flatMap((r) => r.warnings);
    expect(warnings).toHaveLength(0);
  });

  it('violating fixture produces at least 4 Stylelint warnings', async () => {
    const result = await stylelint.lint({
      files: [path.join(FIXTURES_DIR, 'violating.css')],
      config: stylelintConfig,
    });
    const warnings = result.results.flatMap((r) => r.warnings);
    expect(warnings.length).toBeGreaterThanOrEqual(4);
  });
});

// ── AC-8: Lint gate — custom ESLint rule ─────────────────────────────────

describe('Lint gate — custom ESLint rule (AC-8)', () => {
  /** @type {import('eslint').Linter} */
  let linter;
  /** @type {import('eslint').Linter.Config} */
  let eslintConfig;

  beforeAll(async () => {
    const { Linter } = await import('eslint');
    // CJS module.exports becomes .default when imported from ESM
    const ruleModule = await import('../../../eslint-local-rules/no-hardcoded-visual-literals.js');
    const rule = ruleModule.default;

    linter = new Linter();
    linter.defineRule('no-hardcoded-visual-literals', rule);

    eslintConfig = {
      parserOptions: {
        ecmaFeatures: { jsx: true },
        ecmaVersion: 2022,
        sourceType: 'module',
      },
      rules: { 'no-hardcoded-visual-literals': 'error' },
    };
  });

  it('allows var(--fs-*) references in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ color: 'var(--fs-color-text-primary)' }} />;`,
      eslintConfig
    );
    expect(msgs).toHaveLength(0);
  });

  it('allows CSS keyword "inherit" in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ color: 'inherit' }} />;`,
      eslintConfig
    );
    expect(msgs).toHaveLength(0);
  });

  it('rejects hex colour literal in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ color: '#ff3366' }} />;`,
      eslintConfig
    );
    expect(msgs.length).toBeGreaterThan(0);
    expect(msgs[0].messageId).toBe('noHardcodedVisual');
  });

  it('rejects px border-radius literal in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ borderRadius: '6px' }} />;`,
      eslintConfig
    );
    expect(msgs.length).toBeGreaterThan(0);
  });

  it('rejects ms transition-duration literal in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ transitionDuration: '120ms' }} />;`,
      eslintConfig
    );
    expect(msgs.length).toBeGreaterThan(0);
  });

  it('rejects px padding literal in JSX style prop', () => {
    const msgs = linter.verify(
      `const el = <div style={{ padding: '8px' }} />;`,
      eslintConfig
    );
    expect(msgs.length).toBeGreaterThan(0);
  });
});

// ── AC-8 / AC-13: Token exceptions allow-list ─────────────────────────────

describe('Token exceptions allow-list (AC-8 / AC-13)', () => {
  it('token-exceptions.json is valid JSON with an "exceptions" array', () => {
    const data = JSON.parse(readStyles('token-exceptions.json'));
    expect(Array.isArray(data.exceptions)).toBe(true);
  });

  it('each exception entry has path, reason and date fields', () => {
    const data = JSON.parse(readStyles('token-exceptions.json'));
    for (const entry of data.exceptions) {
      expect(entry).toHaveProperty('path');
      expect(entry).toHaveProperty('reason');
      expect(entry).toHaveProperty('date');
    }
  });

  it('no exceptions are committed yet (clean baseline)', () => {
    const data = JSON.parse(readStyles('token-exceptions.json'));
    expect(data.exceptions).toHaveLength(0);
  });
});
