/**
 * generate-token-contract.mjs
 *
 * Parses src/styles/tokens.contract.css and emits src/styles/tokens.contract.json
 * as the machine-readable source of truth consumed by tests and lint rules.
 *
 * Usage: node scripts/generate-token-contract.mjs
 */

import { readFileSync, writeFileSync } from 'fs';
import { join, dirname } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');
const CONTRACT_CSS = join(ROOT, 'src/styles/tokens.contract.css');
const CONTRACT_JSON = join(ROOT, 'src/styles/tokens.contract.json');

/** @param {string} css @returns {string[]} */
function extractTokenNames(css) {
  // Match every custom property name in the file
  const pattern = /(--[\w-]+)\s*:/g;
  const seen = new Set();
  let match;
  while ((match = pattern.exec(css)) !== null) {
    seen.add(match[1]);
  }
  return [...seen].sort();
}

/** @param {string} token @returns {string} */
function groupOf(token) {
  if (/^--fs-|^--fw-|^--lh-|^--ls-|^--font-|^--numeric-/.test(token)) return 'typography';
  if (/^--space-|^--content-max-width|^--gutter/.test(token)) return 'spacing';
  if (/^--radius-/.test(token)) return 'radius';
  if (/^--elevation-|^--scrim/.test(token)) return 'elevation';
  if (/^--duration-|^--easing-/.test(token)) return 'motion';
  if (/^--color-/.test(token)) return 'color';
  return 'other';
}

const css = readFileSync(CONTRACT_CSS, 'utf8');
const tokens = extractTokenNames(css);

/** @type {Record<string, string[]>} */
const groups = {};
for (const token of tokens) {
  const g = groupOf(token);
  if (!groups[g]) groups[g] = [];
  groups[g].push(token);
}

const contract = {
  generated: new Date().toISOString(),
  tokenCount: tokens.length,
  tokens,
  groups,
};

writeFileSync(CONTRACT_JSON, JSON.stringify(contract, null, 2) + '\n');
console.log(
  `[generate-token-contract] ${tokens.length} tokens → src/styles/tokens.contract.json`
);
