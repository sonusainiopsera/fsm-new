#!/usr/bin/env node
/**
 * Design-system adoption audit.
 *
 * Counts:
 *   - token-referencing declarations vs total visual declarations (adoption %)
 *   - bespoke styling declarations (1 - adoption %)
 *   - hardcoded visual literals (hex colours, rgb(), named colours) outside
 *     the dated allow-list in src/styles/token-exceptions.json
 *
 * Exit codes:
 *   0 — all thresholds met
 *   1 — adoption < 95%, bespoke > 5%, or any undeclared literal found
 *
 * Pipeline step: audit:design-system (blocking)
 */

import { readFileSync, readdirSync, statSync } from 'node:fs';
import { join, extname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

import {
  countTotalDeclarations,
  countTokenDeclarations,
  findHardcodedLiterals,
  aggregateMetrics,
} from '../src/a11y/adoptionMetrics.js';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const SRC_DIR = join(__dirname, '../src');
const EXCEPTIONS_FILE = join(__dirname, '../src/styles/token-exceptions.json');

const ADOPTION_THRESHOLD = 95;
const BESPOKE_THRESHOLD = 5;

// ── Load allow-list ──────────────────────────────────────────────────────────
let allowList = [];
try {
  const exceptions = JSON.parse(readFileSync(EXCEPTIONS_FILE, 'utf-8'));
  allowList = Object.keys(exceptions);
} catch {
  console.warn('[audit] Could not read token-exceptions.json — using empty allow-list');
}

// ── Collect CSS files ─────────────────────────────────────────────────────────
function walkCss(dir) {
  const files = [];
  for (const entry of readdirSync(dir)) {
    const full = join(dir, entry);
    const stat = statSync(full);
    if (stat.isDirectory()) {
      files.push(...walkCss(full));
    } else if (extname(entry) === '.css' && !entry.includes('.contract.') && !entry.includes('tokens.')) {
      files.push(full);
    }
  }
  return files;
}

const cssFiles = walkCss(SRC_DIR);

// ── Compute per-file metrics ──────────────────────────────────────────────────
const fileMetrics = [];
const allLiterals = [];

for (const file of cssFiles) {
  const source = readFileSync(file, 'utf-8');
  const total = countTotalDeclarations(source);
  const tokenised = countTokenDeclarations(source);
  const literals = findHardcodedLiterals(source, allowList);

  fileMetrics.push({ total, tokenised, literals: literals.length, file });

  for (const lit of literals) {
    allLiterals.push({ file: relative(SRC_DIR, file), line: lit.line, value: lit.value });
  }
}

// ── Aggregate ─────────────────────────────────────────────────────────────────
const { adoptionPct, bespokePct, totalLiterals } = aggregateMetrics(fileMetrics);

// ── Report ────────────────────────────────────────────────────────────────────
console.log('\n┌─ Design-system Adoption Audit ─────────────────────────────');
console.log(`│  CSS files scanned : ${cssFiles.length}`);
console.log(`│  Adoption          : ${adoptionPct}%  (threshold ≥${ADOPTION_THRESHOLD}%)`);
console.log(`│  Bespoke styling   : ${bespokePct}%  (threshold ≤${BESPOKE_THRESHOLD}%)`);
console.log(`│  Hardcoded literals: ${totalLiterals}  (threshold = 0 outside allow-list)`);
console.log('└────────────────────────────────────────────────────────────\n');

let exitCode = 0;

if (adoptionPct < ADOPTION_THRESHOLD) {
  console.error(`FAIL  Adoption ${adoptionPct}% < required ${ADOPTION_THRESHOLD}%`);
  exitCode = 1;
} else {
  console.log(`PASS  Adoption ${adoptionPct}%`);
}

if (bespokePct > BESPOKE_THRESHOLD) {
  console.error(`FAIL  Bespoke styling ${bespokePct}% > allowed ${BESPOKE_THRESHOLD}%`);
  exitCode = 1;
} else {
  console.log(`PASS  Bespoke styling ${bespokePct}%`);
}

if (totalLiterals > 0) {
  console.error(`FAIL  ${totalLiterals} hardcoded literal(s) found outside allow-list:`);
  for (const lit of allLiterals) {
    console.error(`  ${lit.file}:${lit.line}  →  "${lit.value}"`);
  }
  exitCode = 1;
} else {
  console.log('PASS  No hardcoded literals outside allow-list');
}

process.exit(exitCode);
