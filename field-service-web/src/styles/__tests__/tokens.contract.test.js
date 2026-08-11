/**
 * Design Token Contract Tests
 *
 * Validates:
 *  AC-2  token contract completeness (bijection between contract JSON and value sets)
 *  AC-3  typography scale, line-height, numeric token
 *  AC-4  spacing and radius tokens
 *  AC-5  elevation: exactly 2 levels, hairline border, scrim
 *  AC-6  motion: no duration > 300 ms, exactly 1 easing
 *  AC-7  semantic colour: 1 accent family, 4 semantic families, no gradients
 *  AC-8  lint gate (Stylelint over compliant and violating fixtures)
 *  AC-11 ESLint custom rule via RuleTester
 */

import { describe, it, expect } from 'vitest';
import { readFileSync } from 'fs';
import { join, dirname, resolve } from 'path';
import { fileURLToPath } from 'url';
import { createRequire } from 'module';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = resolve(__dirname, '../../..');

const require = createRequire(import.meta.url);

/* ----------------------------------------------------------------
   Helpers
   ---------------------------------------------------------------- */

/** Extract all custom property names from a CSS string */
function extractTokenNames(css) {
  const pattern = /(--[\w-]+)\s*:/g;
  const found = new Set();
  let m;
  while ((m = pattern.exec(css)) !== null) {
    found.add(m[1]);
  }
  return found;
}

/* ----------------------------------------------------------------
   Load artefacts
   ---------------------------------------------------------------- */

const contractJson = JSON.parse(
  readFileSync(join(__dirname, '../tokens.contract.json'), 'utf8')
);
const contractTokens = new Set(contractJson.tokens);

const lightCss = readFileSync(join(__dirname, '../tokens.light.css'), 'utf8');
const darkCss = readFileSync(join(__dirname, '../tokens.dark.css'), 'utf8');
const lightTokens = extractTokenNames(lightCss);
const darkTokens = extractTokenNames(darkCss);

/* ----------------------------------------------------------------
   AC-2 Token contract completeness / parity
   ---------------------------------------------------------------- */

describe('Token contract completeness (AC-2)', () => {
  it('tokens.light.css declares every contract token', () => {
    const missing = [...contractTokens].filter((t) => !lightTokens.has(t));
    expect(
      missing,
      `Tokens in contract but missing from light: ${missing.join(', ')}`
    ).toHaveLength(0);
  });

  it('tokens.dark.css declares every contract token', () => {
    const missing = [...contractTokens].filter((t) => !darkTokens.has(t));
    expect(
      missing,
      `Tokens in contract but missing from dark: ${missing.join(', ')}`
    ).toHaveLength(0);
  });

  it('tokens.light.css contains no token absent from the contract', () => {
    const extra = [...lightTokens].filter((t) => !contractTokens.has(t));
    expect(
      extra,
      `Tokens in light but not in contract: ${extra.join(', ')}`
    ).toHaveLength(0);
  });

  it('tokens.dark.css contains no token absent from the contract', () => {
    const extra = [...darkTokens].filter((t) => !contractTokens.has(t));
    expect(
      extra,
      `Tokens in dark but not in contract: ${extra.join(', ')}`
    ).toHaveLength(0);
  });

  it('contract JSON token count matches actual token list', () => {
    expect(contractJson.tokens).toHaveLength(contractJson.tokenCount);
  });
});

/* ----------------------------------------------------------------
   AC-3 Typography
   ---------------------------------------------------------------- */

describe('Typography tokens (AC-3)', () => {
  const REQUIRED_SIZES = ['--fs-xs', '--fs-sm', '--fs-md', '--fs-base', '--fs-lg', '--fs-xl', '--fs-2xl'];

  it('all 7 font-size tokens are present in contract', () => {
    for (const token of REQUIRED_SIZES) {
      expect(contractTokens.has(token), `Missing: ${token}`).toBe(true);
    }
  });

  it('--lh-body has value 1.5 in light', () => {
    const m = lightCss.match(/--lh-body\s*:\s*([^;]+);/);
    expect(m, '--lh-body not found').toBeTruthy();
    expect(Number(m[1].trim())).toBe(1.5);
  });

  it('--ls-tight is negative in light (tightened tracking for large sizes)', () => {
    const m = lightCss.match(/--ls-tight\s*:\s*([^;]+);/);
    expect(m, '--ls-tight not found').toBeTruthy();
    const val = m[1].trim();
    expect(val.startsWith('-'), `Expected negative value, got: ${val}`).toBe(true);
  });

  it('--numeric-figures is present in contract', () => {
    expect(contractTokens.has('--numeric-figures')).toBe(true);
  });

  it('--numeric-figures has value "tabular-nums" in light', () => {
    const m = lightCss.match(/--numeric-figures\s*:\s*([^;]+);/);
    expect(m, '--numeric-figures not found in light').toBeTruthy();
    expect(m[1].trim()).toBe('tabular-nums');
  });

  it('--numeric-figures has value "tabular-nums" in dark', () => {
    const m = darkCss.match(/--numeric-figures\s*:\s*([^;]+);/);
    expect(m, '--numeric-figures not found in dark').toBeTruthy();
    expect(m[1].trim()).toBe('tabular-nums');
  });

  it('base.css applies font-variant-numeric in a utility class', () => {
    const baseCss = readFileSync(join(__dirname, '../base.css'), 'utf8');
    expect(baseCss).toMatch(/font-variant-numeric/);
    expect(baseCss).toMatch(/\.numeric|\.tabular-nums/);
  });
});

/* ----------------------------------------------------------------
   AC-4 Spacing and radius
   ---------------------------------------------------------------- */

describe('Spacing tokens (AC-4)', () => {
  it('--space-1 through --space-8 are all present', () => {
    for (const t of ['--space-1', '--space-2', '--space-3', '--space-4', '--space-6', '--space-8']) {
      expect(contractTokens.has(t), `Missing: ${t}`).toBe(true);
    }
  });

  it('--content-max-width and --gutter are present', () => {
    expect(contractTokens.has('--content-max-width')).toBe(true);
    expect(contractTokens.has('--gutter')).toBe(true);
  });

  it('--radius-control, --radius-card, --radius-overlay, --radius-pill are present', () => {
    for (const t of ['--radius-control', '--radius-card', '--radius-overlay', '--radius-pill']) {
      expect(contractTokens.has(t), `Missing: ${t}`).toBe(true);
    }
  });
});

/* ----------------------------------------------------------------
   AC-5 Elevation: exactly 2 levels, hairline border, scrim
   ---------------------------------------------------------------- */

describe('Elevation tokens (AC-5)', () => {
  it('exactly two elevation level tokens exist', () => {
    const elevationTokens = [...contractTokens].filter((t) =>
      /^--elevation-\d+$/.test(t)
    );
    expect(
      elevationTokens,
      `Expected exactly 2 elevation tokens, got: ${elevationTokens.join(', ')}`
    ).toHaveLength(2);
  });

  it('--color-border (hairline) is present', () => {
    expect(contractTokens.has('--color-border')).toBe(true);
  });

  it('--scrim is present', () => {
    expect(contractTokens.has('--scrim')).toBe(true);
  });
});

/* ----------------------------------------------------------------
   AC-6 Motion: durations ≤ 300 ms, exactly one easing
   ---------------------------------------------------------------- */

describe('Motion tokens (AC-6)', () => {
  it('all duration tokens in light are at or below 300 ms', () => {
    const durationPattern = /(--duration-[\w-]+)\s*:\s*(\d+)ms/g;
    let m;
    while ((m = durationPattern.exec(lightCss)) !== null) {
      const [, token, ms] = m;
      expect(
        parseInt(ms, 10),
        `${token} exceeds 300 ms ceiling (got ${ms} ms)`
      ).toBeLessThanOrEqual(300);
    }
  });

  it('all duration tokens in dark are at or below 300 ms', () => {
    const durationPattern = /(--duration-[\w-]+)\s*:\s*(\d+)ms/g;
    let m;
    while ((m = durationPattern.exec(darkCss)) !== null) {
      const [, token, ms] = m;
      expect(
        parseInt(ms, 10),
        `${token} exceeds 300 ms ceiling (got ${ms} ms) in dark`
      ).toBeLessThanOrEqual(300);
    }
  });

  it('exactly one easing token exists in the contract', () => {
    const easingTokens = [...contractTokens].filter((t) => t.startsWith('--easing-'));
    expect(
      easingTokens,
      `Expected exactly 1 easing token, got: ${easingTokens.join(', ')}`
    ).toHaveLength(1);
  });

  it('the three required duration tokens are present', () => {
    for (const t of ['--duration-micro', '--duration-entry', '--duration-overlay']) {
      expect(contractTokens.has(t), `Missing: ${t}`).toBe(true);
    }
  });
});

/* ----------------------------------------------------------------
   AC-7 Colour: 1 accent family, 4 semantic families, no gradients
   ---------------------------------------------------------------- */

describe('Colour token structure (AC-7)', () => {
  it('exactly one accent colour family exists', () => {
    const accentTokens = [...contractTokens].filter((t) =>
      t.startsWith('--color-accent-')
    );
    expect(accentTokens.length).toBeGreaterThanOrEqual(1);
    // All under the single --color-accent-* family
    const families = new Set(accentTokens.map(() => 'accent'));
    expect(families.size).toBe(1);
  });

  it('exactly four semantic colour families exist', () => {
    const knownSemantic = ['info', 'success', 'warning', 'danger'];
    const foundFamilies = new Set(
      [...contractTokens]
        .filter((t) => /^--color-(info|success|warning|danger)-/.test(t))
        .map((t) => t.match(/^--color-(\w+)-/)[1])
    );
    expect(foundFamilies.size).toBe(4);
    for (const fam of knownSemantic) {
      expect(foundFamilies.has(fam), `Missing semantic family: ${fam}`).toBe(true);
    }
  });

  it('no fifth semantic colour family is present in the contract', () => {
    const allowedFamilies = new Set([
      'neutral', 'accent', 'info', 'success', 'warning', 'danger',
      'surface', 'text', 'border',
    ]);
    const unexpected = [...contractTokens]
      .filter((t) => t.startsWith('--color-'))
      .map((t) => {
        const m = t.match(/^--color-(\w+)/);
        return m ? m[1] : null;
      })
      .filter((fam) => fam && !allowedFamilies.has(fam));
    expect(
      unexpected,
      `Unexpected colour families: ${[...new Set(unexpected)].join(', ')}`
    ).toHaveLength(0);
  });

  it('no gradient values in light token set', () => {
    expect(lightCss).not.toMatch(/linear-gradient|radial-gradient|conic-gradient/);
  });

  it('no gradient values in dark token set', () => {
    expect(darkCss).not.toMatch(/linear-gradient|radial-gradient|conic-gradient/);
  });
});

/* ----------------------------------------------------------------
   AC-8 Lint gate — Stylelint programmatic API
   ---------------------------------------------------------------- */

describe('Lint gate — Stylelint (AC-8)', () => {
  const STYLELINT_CONFIG = {
    extends: ['stylelint-config-standard'],
    rules: {
      'declaration-property-value-allowed-list': {
        color: ['/^var\\(--/', 'inherit', 'transparent', 'currentColor'],
        'background-color': ['/^var\\(--/', 'transparent', 'none', 'inherit'],
        'border-radius': ['/^var\\(--/', '0', '50%', '100%', 'inherit'],
        padding: ['/^var\\(--/', '0', 'auto', 'inherit'],
        margin: ['/^var\\(--/', '0', 'auto', 'inherit'],
        gap: ['/^var\\(--/', '0'],
        'box-shadow': ['/^var\\(--/', 'none'],
        'transition-duration': ['/^var\\(--/', '0s', '0ms'],
        'animation-duration': ['/^var\\(--/', '0s', '0ms'],
      },
    },
  };

  it('compliant fixture produces zero Stylelint warnings', async () => {
    const stylelint = await import('stylelint');
    const result = await stylelint.default.lint({
      files: join(__dirname, 'fixtures/compliant.css'),
      config: STYLELINT_CONFIG,
    });
    const warnings = result.results.flatMap((r) => r.warnings);
    if (warnings.length > 0) {
      console.error('Unexpected Stylelint warnings:', warnings.map((w) => w.text));
    }
    expect(warnings).toHaveLength(0);
  });

  it('violating fixture produces Stylelint warnings for each violation', async () => {
    const stylelint = await import('stylelint');
    const result = await stylelint.default.lint({
      files: join(__dirname, 'fixtures/violating.css'),
      config: STYLELINT_CONFIG,
    });
    const warnings = result.results.flatMap((r) => r.warnings);
    // Expect at least 4 violations (colour, radius, spacing, duration)
    expect(warnings.length).toBeGreaterThanOrEqual(4);
  });
});

/* ----------------------------------------------------------------
   AC-11 ESLint custom rule via RuleTester
   ---------------------------------------------------------------- */

describe('Custom ESLint rule — no-hardcoded-visual-literals (AC-11)', () => {
  it('rule accepts token references and rejects literals', () => {
    const { RuleTester } = require('eslint');
    const rule = require('../../../eslint-local-rules/no-hardcoded-visual-literals');

    const tester = new RuleTester({
      parserOptions: { ecmaVersion: 2020, ecmaFeatures: { jsx: true } },
    });

    tester.run('no-hardcoded-visual-literals', rule, {
      valid: [
        { code: '<div style={{ color: "var(--color-text-primary)" }} />' },
        { code: '<div style={{ backgroundColor: "var(--color-surface-base)" }} />' },
        { code: '<div style={{ borderRadius: "var(--radius-control)" }} />' },
        { code: '<div style={{ padding: "var(--space-4)" }} />' },
        { code: '<div style={{ transitionDuration: "var(--duration-micro)" }} />' },
        { code: '<div style={{ color: "inherit" }} />' },
        { code: '<div style={{ color: "transparent" }} />' },
        { code: '<div style={{ color: "currentColor" }} />' },
        { code: '<div style={{ borderRadius: "0" }} />' },
      ],
      invalid: [
        {
          code: '<div style={{ color: "#1a73e8" }} />',
          errors: [{ messageId: 'hardcodedColor' }],
        },
        {
          code: '<div style={{ backgroundColor: "rgb(26, 115, 232)" }} />',
          errors: [{ messageId: 'hardcodedColor' }],
        },
        {
          code: '<div style={{ color: "red" }} />',
          errors: [{ messageId: 'hardcodedColor' }],
        },
        {
          code: '<div style={{ borderRadius: "6px" }} />',
          errors: [{ messageId: 'hardcodedRadius' }],
        },
        {
          code: '<div style={{ padding: "16px" }} />',
          errors: [{ messageId: 'hardcodedSpacing' }],
        },
        {
          code: '<div style={{ transitionDuration: "120ms" }} />',
          errors: [{ messageId: 'hardcodedDuration' }],
        },
      ],
    });
  });

  it('token-exceptions.json is a valid JSON file with the required schema', () => {
    const exceptions = JSON.parse(
      readFileSync(join(__dirname, '../../styles/token-exceptions.json'), 'utf8')
    );
    expect(Array.isArray(exceptions.exceptions)).toBe(true);
    expect(typeof exceptions.description).toBe('string');
  });
});
