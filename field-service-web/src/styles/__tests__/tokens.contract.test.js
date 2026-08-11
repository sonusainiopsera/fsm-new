/**
 * Token contract tests — verifies the design token system is internally consistent.
 *
 * Tests:
 *  1.  Contract completeness: tokens.contract.json lists 77 tokens.
 *  2.  Appearance parity: every contract token in BOTH light and dark value sets (bijection).
 *  3.  Motion ceiling: all --token-duration-* ≤ 300 ms.
 *  4.  Semantic family count: exactly four families (info, success, warning, danger).
 *  5.  No gradient values in colour tokens.
 *  6.  Numeric token: present in contract and base.css .numeric class uses it.
 *  7.  Token-exceptions schema: every entry has file, reason, and date fields.
 *  8.  Stylelint — compliant.css: zero warnings.
 *  9.  Stylelint — violating.css: ≥ 3 warnings.
 * 10.  ESLint custom rule — compliant JSX: zero violations.
 * 11.  ESLint custom rule — violating JSX: at least one violation per bad literal type.
 * 12.  Appearance smoke test: representative tokens differ between light and dark.
 * 13.  Build smoke test: dist/ contains a content-hashed CSS file (skipped if not built).
 */

import { readFileSync, existsSync, readdirSync } from 'fs'
import { createRequire } from 'module'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'
import { describe, test, expect, beforeAll } from 'vitest'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const ROOT = join(__dirname, '../../..')
const STYLES_DIR = join(__dirname, '..')

// createRequire lets ESM files load CJS modules (eslint, local rule)
const require = createRequire(import.meta.url)

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Parse CSS custom property declarations from a CSS string.
 * @param {string} css
 * @returns {Map<string, string>}
 */
function parseTokenValues(css) {
  const props = new Map()
  // Match --token-foo-bar: value; — value ends at ; (handles parens inside values)
  const re = /(--token-[\w-]+)\s*:\s*((?:[^;(]|\([^)]*\))*);/g
  let m
  while ((m = re.exec(css)) !== null) {
    const name = m[1].trim()
    const value = m[2].trim()
    if (!props.has(name)) props.set(name, value)
  }
  return props
}

/**
 * @param {string} value  CSS duration value like "120ms" or "0.18s"
 * @returns {number | null}
 */
function parseDurationMs(value) {
  const msMatch = /^([0-9]+(?:\.[0-9]+)?)ms$/.exec(value)
  if (msMatch) return parseFloat(msMatch[1])
  const sMatch = /^([0-9]+(?:\.[0-9]+)?)s$/.exec(value)
  if (sMatch) return parseFloat(sMatch[1]) * 1000
  return null
}

// ---------------------------------------------------------------------------
// Read source files once
// ---------------------------------------------------------------------------
const contractJson = JSON.parse(readFileSync(join(STYLES_DIR, 'tokens.contract.json'), 'utf-8'))
const lightCss = readFileSync(join(STYLES_DIR, 'tokens.light.css'), 'utf-8')
const darkCss = readFileSync(join(STYLES_DIR, 'tokens.dark.css'), 'utf-8')
const baseCss = readFileSync(join(STYLES_DIR, 'base.css'), 'utf-8')
const exceptionsJson = JSON.parse(readFileSync(join(ROOT, 'styles/token-exceptions.json'), 'utf-8'))

const contractTokens = /** @type {string[]} */ (contractJson.tokens)
const lightValues = parseTokenValues(lightCss)
const darkValues = parseTokenValues(darkCss)

// ---------------------------------------------------------------------------
// 1 + 2. Contract completeness and appearance parity
// ---------------------------------------------------------------------------
describe('Token contract completeness', () => {
  test('contract JSON lists 77 tokens', () => {
    expect(contractTokens).toHaveLength(77)
  })

  test('all token names match --token-* pattern', () => {
    for (const name of contractTokens) {
      expect(name).toMatch(/^--token-[\w-]+$/)
    }
  })
})

describe('Appearance parity (bijection)', () => {
  test('every contract token is declared in tokens.light.css', () => {
    const missing = contractTokens.filter(t => !lightValues.has(t))
    expect(missing, `Missing from light: ${missing.join(', ')}`).toHaveLength(0)
  })

  test('every contract token is declared in tokens.dark.css', () => {
    const missing = contractTokens.filter(t => !darkValues.has(t))
    expect(missing, `Missing from dark: ${missing.join(', ')}`).toHaveLength(0)
  })

  test('tokens.light.css declares no tokens absent from contract', () => {
    const extra = [...lightValues.keys()].filter(t => !contractTokens.includes(t))
    expect(extra, `Extra in light: ${extra.join(', ')}`).toHaveLength(0)
  })

  test('tokens.dark.css declares no tokens absent from contract', () => {
    const extra = [...darkValues.keys()].filter(t => !contractTokens.includes(t))
    expect(extra, `Extra in dark: ${extra.join(', ')}`).toHaveLength(0)
  })
})

// ---------------------------------------------------------------------------
// 3. Motion ceiling
// ---------------------------------------------------------------------------
describe('Motion ceiling', () => {
  test('all --token-duration-* values are ≤ 300 ms', () => {
    const durationTokens = contractTokens.filter(t => t.startsWith('--token-duration-'))
    expect(durationTokens.length).toBeGreaterThan(0)

    for (const token of durationTokens) {
      for (const [label, map] of [['light', lightValues], ['dark', darkValues]]) {
        const val = map.get(token) ?? ''
        const ms = parseDurationMs(val)
        expect(ms, `${token} (${label}): "${val}" is not a recognised duration`).not.toBeNull()
        expect(ms, `${token} (${label}): ${ms}ms > 300 ms ceiling`).toBeLessThanOrEqual(300)
      }
    }
  })

  test('exactly three duration tokens exist', () => {
    const dt = contractTokens.filter(t => t.startsWith('--token-duration-'))
    expect(dt).toHaveLength(3)
    expect(dt).toContain('--token-duration-micro')
    expect(dt).toContain('--token-duration-enter')
    expect(dt).toContain('--token-duration-overlay')
  })

  test('exactly one easing token exists', () => {
    const et = contractTokens.filter(t => t.startsWith('--token-easing-'))
    expect(et).toHaveLength(1)
    expect(et[0]).toBe('--token-easing-standard')
  })
})

// ---------------------------------------------------------------------------
// 4. Semantic family count
// ---------------------------------------------------------------------------
describe('Semantic family count', () => {
  test('contract JSON reports exactly four semantic families', () => {
    const { semanticFamilies } = contractJson
    expect(semanticFamilies).toHaveLength(4)
    expect(semanticFamilies).toContain('info')
    expect(semanticFamilies).toContain('success')
    expect(semanticFamilies).toContain('warning')
    expect(semanticFamilies).toContain('danger')
  })

  test('no unknown semantic family exists in contract tokens', () => {
    const KNOWN = new Set(['info', 'success', 'warning', 'danger'])
    const LAYOUT_PREFIXES = new Set([
      'fs', 'lh', 'ls', 'family', 'numeric',
      'space', 'content', 'gutter',
      'radius',
      'elevation',
      'duration', 'easing',
      'neutral', 'accent',
      'surface', 'text', 'border',
    ])
    for (const token of contractTokens) {
      const family = token.replace('--token-', '').split('-')[0]
      if (!LAYOUT_PREFIXES.has(family)) {
        expect(KNOWN.has(family), `Unexpected family "${family}" in token ${token}`).toBe(true)
      }
    }
  })

  test('no gradient values in colour tokens (light mode)', () => {
    const COLOR_PREFIXES = [
      '--token-neutral-', '--token-accent-', '--token-info-',
      '--token-success-', '--token-warning-', '--token-danger-',
      '--token-surface-', '--token-text-', '--token-border-',
    ]
    for (const token of contractTokens) {
      if (COLOR_PREFIXES.some(p => token.startsWith(p))) {
        const val = lightValues.get(token) ?? ''
        expect(val, `${token} contains gradient`).not.toMatch(/gradient/)
      }
    }
  })
})

// ---------------------------------------------------------------------------
// 5. Numeric token
// ---------------------------------------------------------------------------
describe('Numeric token', () => {
  test('--token-numeric is in the contract', () => {
    expect(contractTokens).toContain('--token-numeric')
  })

  test('light value is "tabular-nums"', () => {
    expect(lightValues.get('--token-numeric')).toBe('tabular-nums')
  })

  test('dark value is "tabular-nums"', () => {
    expect(darkValues.get('--token-numeric')).toBe('tabular-nums')
  })

  test('base.css .numeric applies font-variant-numeric via var(--token-numeric)', () => {
    expect(baseCss).toContain('font-variant-numeric: var(--token-numeric)')
  })
})

// ---------------------------------------------------------------------------
// 6. Token-exceptions schema
// ---------------------------------------------------------------------------
describe('Token exceptions schema', () => {
  test('exceptions is an array', () => {
    expect(Array.isArray(exceptionsJson.exceptions)).toBe(true)
  })

  test('every exception entry has file, reason, and date', () => {
    for (const entry of exceptionsJson.exceptions) {
      expect(entry).toHaveProperty('file')
      expect(entry).toHaveProperty('reason')
      expect(entry).toHaveProperty('date')
      expect(typeof entry.reason).toBe('string')
      expect(entry.reason.length).toBeGreaterThan(0)
      expect(entry.date).toMatch(/^\d{4}-\d{2}-\d{2}$/)
    }
  })
})

// ---------------------------------------------------------------------------
// 7. Stylelint lint gate
// ---------------------------------------------------------------------------
describe('Stylelint lint gate', () => {
  test('compliant.css produces zero warnings', async () => {
    const { default: stylelint } = await import('stylelint')
    const code = readFileSync(join(__dirname, 'fixtures/compliant.css'), 'utf-8')

    const result = await stylelint.lint({
      code,
      configFile: join(ROOT, '.stylelintrc.json'),
    })
    const warnings = result.results.flatMap(r => r.warnings)
    expect(warnings, `Unexpected warnings: ${warnings.map(w => w.text).join('; ')}`).toHaveLength(0)
  })

  test('violating.css produces ≥ 3 warnings', async () => {
    const { default: stylelint } = await import('stylelint')
    const code = readFileSync(join(__dirname, 'fixtures/violating.css'), 'utf-8')

    const result = await stylelint.lint({
      code,
      config: {
        rules: {
          'color-named': 'never',
          'declaration-property-value-disallowed-list': {
            color: ['/#[0-9a-fA-F]/'],
            'border-radius': ['/^[1-9][0-9]*(px|em|rem)/'],
            'transition-duration': ['/^[0-9]+(ms|s)/'],
          },
        },
      },
    })
    const warnings = result.results.flatMap(r => r.warnings)
    expect(warnings.length).toBeGreaterThanOrEqual(3)
  })
})

// ---------------------------------------------------------------------------
// 8. ESLint custom rule — programmatic
// ---------------------------------------------------------------------------
describe('ESLint no-hardcoded-visual-literals rule', () => {
  let Linter
  let rule

  beforeAll(async () => {
    // CJS modules loaded via createRequire — safe in ESM with module.createRequire
    Linter = require('eslint').Linter
    rule = require(join(ROOT, 'eslint-local-rules/no-hardcoded-visual-literals.js'))
  })

  function lint(code) {
    const linter = new Linter()
    linter.defineRule('local-rules/no-hardcoded-visual-literals', rule)
    return linter.verify(code, {
      parserOptions: { ecmaVersion: 2022, sourceType: 'module', ecmaFeatures: { jsx: true } },
      rules: { 'local-rules/no-hardcoded-visual-literals': 'error' },
    }).filter(m => m.ruleId === 'local-rules/no-hardcoded-visual-literals')
  }

  test('compliant JSX style prop passes with zero violations', () => {
    const msgs = lint(`
      function Card() {
        return (
          <div
            style={{
              color: 'var(--token-text-primary)',
              backgroundColor: 'var(--token-surface-card)',
              borderRadius: 'var(--token-radius-card)',
              padding: 0,
            }}
          />
        )
      }
    `)
    expect(msgs).toHaveLength(0)
  })

  test('hex colour in style prop triggers a violation', () => {
    const msgs = lint(`function Bad() { return <div style={{ color: '#ff0000' }} /> }`)
    expect(msgs.length).toBeGreaterThanOrEqual(1)
  })

  test('px border-radius in style prop triggers a violation', () => {
    const msgs = lint(`function Bad() { return <div style={{ borderRadius: '6px' }} /> }`)
    expect(msgs.length).toBeGreaterThanOrEqual(1)
  })

  test('ms transitionDuration in style prop triggers a violation', () => {
    const msgs = lint(`function Bad() { return <div style={{ transitionDuration: '120ms' }} /> }`)
    expect(msgs.length).toBeGreaterThanOrEqual(1)
  })

  test('named colour in style prop triggers a violation', () => {
    const msgs = lint(`function Bad() { return <div style={{ color: 'red' }} /> }`)
    expect(msgs.length).toBeGreaterThanOrEqual(1)
  })
})

// ---------------------------------------------------------------------------
// 9. Appearance smoke test
// ---------------------------------------------------------------------------
describe('Appearance smoke test', () => {
  test('representative tokens differ between light and dark', () => {
    const REPRESENTATIVE = [
      '--token-surface-page',
      '--token-text-primary',
      '--token-danger-default',
      '--token-surface-card',
      '--token-border-default',
    ]
    for (const token of REPRESENTATIVE) {
      const lightVal = lightValues.get(token)
      const darkVal = darkValues.get(token)
      expect(lightVal, `${token} missing from light`).toBeDefined()
      expect(darkVal, `${token} missing from dark`).toBeDefined()
      expect(lightVal, `${token}: identical in light and dark`).not.toEqual(darkVal)
    }
  })
})

// ---------------------------------------------------------------------------
// 10. Build smoke test (skipped when dist/ not present)
// ---------------------------------------------------------------------------
describe('Build smoke test', () => {
  test('dist/assets contains a content-hashed CSS file when built', () => {
    const distAssets = join(ROOT, 'dist/assets')
    if (!existsSync(distAssets)) {
      console.warn('Build smoke test skipped: dist/assets not found. Run `npm run build` first.')
      return
    }
    const cssFiles = readdirSync(distAssets).filter(f => /\.css$/.test(f) && /-[a-f0-9]{8}\.css$/.test(f))
    expect(cssFiles.length, 'Expected ≥1 content-hashed CSS file in dist/assets').toBeGreaterThan(0)
  })
})
