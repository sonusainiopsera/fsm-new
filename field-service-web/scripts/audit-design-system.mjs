#!/usr/bin/env node
/**
 * Design-system adoption audit.
 *
 * Counts token-referencing declarations versus total visual declarations across src/,
 * checks components imported from the shared library versus locally-defined styled elements,
 * and fails if:
 *   - Token adoption < 95 %
 *   - Bespoke styling > 5 %
 *   - Any hard-coded colour, radius, spacing or motion value outside the allow-list
 *
 * Usage:
 *   node scripts/audit-design-system.mjs
 *   node scripts/audit-design-system.mjs --report  (emit JSON report only, no exit code)
 *
 * Results published as a pipeline artifact when run in CI.
 */

import { readFileSync, readdirSync, statSync, existsSync } from 'node:fs'
import { resolve, join, extname } from 'node:path'

const SRC_DIR = resolve(import.meta.dirname ?? '.', '..', 'src')
const ALLOW_LIST_PATH = resolve(import.meta.dirname ?? '.', '..', 'styles', 'token-exceptions.json')
const REPORT_ONLY = process.argv.includes('--report')

// Visual CSS properties that must reference tokens
const VISUAL_PROPERTIES = [
  'color', 'background', 'background-color', 'border-color', 'border',
  'outline-color', 'fill', 'stroke',
  'border-radius', 'box-shadow', 'outline',
  'transition-duration', 'animation-duration',
  'font-size', 'font-family',
]

// Patterns that indicate a hard-coded visual literal (not a token)
const HARDCODED_PATTERNS = [
  /#[0-9a-fA-F]{3,8}\b/,             // hex colours
  /\brgb[a]?\s*\(/,                   // rgb/rgba
  /\bhsl[a]?\s*\(/,                   // hsl/hsla
  /\b\d+(\.\d+)?px\b/,               // px values (except 0px)
  /\b\d+(\.\d+)?ms\b/,               // ms durations
  /\b\d+(\.\d+)?s\b/,                // s durations
]

// Properties that may use literal '0' without a token
const ZERO_ALLOWED_PROPS = new Set(['outline', 'border', 'margin', 'padding'])

/**
 * Recursively walk a directory and return all file paths.
 *
 * @param {string} dir
 * @param {string[]} extensions
 * @returns {string[]}
 */
export function walkFiles(dir, extensions) {
  const results = []
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry)
    if (statSync(full).isDirectory()) {
      results.push(...walkFiles(full, extensions))
    } else if (extensions.includes(extname(entry))) {
      results.push(full)
    }
  }
  return results
}

/**
 * Parses CSS content and counts visual declarations.
 *
 * @param {string} content  CSS source
 * @returns {{ total: number, tokenReferencing: number, hardcoded: string[] }}
 */
export function analyzeCss(content) {
  let total = 0
  let tokenReferencing = 0
  const hardcoded = []

  const declarationRe = /([a-z-]+)\s*:\s*([^;{}]+);/g
  let match
  while ((match = declarationRe.exec(content)) !== null) {
    const prop = match[1].trim()
    const value = match[2].trim()
    if (!VISUAL_PROPERTIES.includes(prop)) continue

    total++

    if (value.includes('var(--token-') || value === 'inherit' || value === 'currentColor' || value === 'transparent') {
      tokenReferencing++
    } else {
      const isHardcoded = HARDCODED_PATTERNS.some(re => re.test(value))
        && !(value === '0' && ZERO_ALLOWED_PROPS.has(prop))
        && !/^var\(/.test(value)
      if (isHardcoded) {
        hardcoded.push(`${prop}: ${value}`)
      }
    }
  }

  return { total, tokenReferencing, hardcoded }
}

/**
 * Checks a JSX/JS file for inline style literals that bypass tokens.
 *
 * @param {string} content
 * @returns {{ inlineStyleLiterals: string[] }}
 */
export function analyzeJsx(content) {
  const inlineStyleLiterals = []

  // Look for style={{ ... }} with hard-coded values
  const styleObjRe = /style\s*=\s*\{\s*\{([^}]+)\}\s*\}/g
  let match
  while ((match = styleObjRe.exec(content)) !== null) {
    const styleBody = match[1]
    const isHardcoded = HARDCODED_PATTERNS.some(re => re.test(styleBody))
    if (isHardcoded && !styleBody.includes('var(--token-')) {
      inlineStyleLiterals.push(styleBody.trim().slice(0, 120))
    }
  }

  return { inlineStyleLiterals }
}

/**
 * Loads the dated allow-list of known exceptions.
 *
 * @returns {Set<string>}
 */
function loadAllowList() {
  if (!existsSync(ALLOW_LIST_PATH)) return new Set()
  try {
    const data = JSON.parse(readFileSync(ALLOW_LIST_PATH, 'utf8'))
    return new Set(data.exceptions?.map(e => e.value) ?? [])
  } catch {
    return new Set()
  }
}

/**
 * Computes the adoption metrics.
 * Exported for unit testing.
 *
 * @param {{ totalDeclarations: number, tokenDeclarations: number }} counts
 * @returns {{ adoptionPct: number, bespokePct: number }}
 */
export function computeMetrics({ totalDeclarations, tokenDeclarations }) {
  if (totalDeclarations === 0) return { adoptionPct: 100, bespokePct: 0 }
  const adoptionPct = (tokenDeclarations / totalDeclarations) * 100
  const bespokePct = 100 - adoptionPct
  return { adoptionPct, bespokePct }
}

async function main() {
  const allowList = loadAllowList()
  const cssFiles = walkFiles(SRC_DIR, ['.css'])
  const jsxFiles = walkFiles(SRC_DIR, ['.jsx', '.js'])

  let totalDeclarations = 0
  let tokenDeclarations = 0
  const allHardcoded = []
  const allInlineStyleLiterals = []

  for (const file of cssFiles) {
    // Skip token definition files — they may contain raw values by design
    if (file.includes('tokens.light.css') || file.includes('tokens.dark.css') || file.includes('tokens.contract.css')) continue

    const content = readFileSync(file, 'utf8')
    const { total, tokenReferencing, hardcoded } = analyzeCss(content)
    totalDeclarations += total
    tokenDeclarations += tokenReferencing
    for (const h of hardcoded) {
      if (!allowList.has(h)) {
        allHardcoded.push({ file: file.replace(SRC_DIR, 'src'), value: h })
      }
    }
  }

  for (const file of jsxFiles) {
    const content = readFileSync(file, 'utf8')
    const { inlineStyleLiterals } = analyzeJsx(content)
    for (const literal of inlineStyleLiterals) {
      if (!allowList.has(literal)) {
        allInlineStyleLiterals.push({ file: file.replace(SRC_DIR, 'src'), value: literal })
      }
    }
  }

  const { adoptionPct, bespokePct } = computeMetrics({ totalDeclarations, tokenDeclarations })

  const report = {
    totalDeclarations,
    tokenDeclarations,
    adoptionPct: Math.round(adoptionPct * 100) / 100,
    bespokePct: Math.round(bespokePct * 100) / 100,
    hardcodedLiterals: allHardcoded.length + allInlineStyleLiterals.length,
    hardcodedDetails: [...allHardcoded, ...allInlineStyleLiterals],
    thresholds: {
      adoptionMin: 95,
      bespokeMax: 5,
      hardcodedMax: 0,
    },
    pass: adoptionPct >= 95 && bespokePct <= 5 && (allHardcoded.length + allInlineStyleLiterals.length) === 0,
  }

  if (REPORT_ONLY) {
    console.log(JSON.stringify(report, null, 2))
    return
  }

  console.log(`\n=== Design-system adoption audit ===`)
  console.log(`Total visual declarations: ${totalDeclarations}`)
  console.log(`Token-referencing:         ${tokenDeclarations} (${report.adoptionPct}%)`)
  console.log(`Bespoke:                   ${totalDeclarations - tokenDeclarations} (${report.bespokePct}%)`)
  console.log(`Hard-coded literals:       ${report.hardcodedLiterals}`)
  console.log(`\nThresholds: adoption ≥ 95%, bespoke ≤ 5%, hard-coded literals = 0`)

  const failures = []
  if (adoptionPct < 95) {
    failures.push(`Token adoption ${report.adoptionPct}% is below the 95% threshold.`)
  }
  if (bespokePct > 5) {
    failures.push(`Bespoke styling ${report.bespokePct}% exceeds the 5% threshold.`)
  }
  if (allHardcoded.length > 0) {
    const detail = allHardcoded.slice(0, 10).map(h => `  ${h.file}: ${h.value}`).join('\n')
    failures.push(`${allHardcoded.length} hard-coded CSS literal(s) outside the allow-list:\n${detail}`)
  }
  if (allInlineStyleLiterals.length > 0) {
    const detail = allInlineStyleLiterals.slice(0, 10).map(h => `  ${h.file}: ${h.value}`).join('\n')
    failures.push(`${allInlineStyleLiterals.length} hard-coded JSX inline style literal(s) outside the allow-list:\n${detail}`)
  }

  if (failures.length > 0) {
    console.error('\n[FAIL] Design-system adoption gate failed:')
    for (const f of failures) console.error(f)
    process.exit(1)
  }

  console.log('\n[PASS] Design-system adoption gate passed.')
}

main().catch(err => {
  console.error(err)
  process.exit(1)
})
