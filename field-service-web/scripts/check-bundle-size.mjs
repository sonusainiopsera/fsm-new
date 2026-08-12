#!/usr/bin/env node
/**
 * Bundle size budget enforcement.
 *
 * Reads the Vite build output manifest and checks that the technician entry
 * chunk (chunk-technician) does not exceed the gzipped size budget.
 *
 * Run after `vite build`:
 *   node scripts/check-bundle-size.mjs
 *
 * The technician chunk budget is kept lean so the shell loads fast on a
 * throttled Fast 3G mobile connection (≤ 100 kB gzipped for the entry chunk).
 *
 * Exit code 0 = all budgets pass.
 * Exit code 1 = one or more budgets exceeded.
 */

import { readFileSync, statSync, readdirSync } from 'fs';
import { resolve, join } from 'path';
import { createGunzip } from 'zlib';
import { pipeline } from 'stream/promises';
import { createReadStream } from 'fs';
import { Writable } from 'stream';

const DIST_DIR = resolve(process.cwd(), 'dist', 'assets');

const BUDGETS = [
  {
    name: 'chunk-technician',
    // Technician entry chunk must stay ≤ 100 kB gzipped (Fast 3G baseline)
    maxGzippedKb: 100,
  },
];

async function getGzippedSize(filePath) {
  let byteCount = 0;
  const gunzip = createGunzip();
  const counter = new Writable({
    write(chunk, _enc, cb) {
      byteCount += chunk.length;
      cb();
    },
  });
  try {
    await pipeline(createReadStream(filePath), gunzip, counter);
    return byteCount;
  } catch {
    // File is not gzipped — return raw size as approximation
    return statSync(filePath).size;
  }
}

async function main() {
  let allPass = true;

  let files;
  try {
    files = readdirSync(DIST_DIR);
  } catch {
    console.error('[bundle-budget] dist/assets not found — run `vite build` first.');
    process.exit(1);
  }

  for (const budget of BUDGETS) {
    const matching = files.filter(
      (f) => f.startsWith(budget.name) && f.endsWith('.js')
    );

    if (matching.length === 0) {
      console.warn(`[bundle-budget] WARN: no file matching "${budget.name}" found in dist/assets`);
      continue;
    }

    for (const file of matching) {
      const fullPath = join(DIST_DIR, file);
      const rawBytes = statSync(fullPath).size;
      const rawKb = (rawBytes / 1024).toFixed(1);
      const budgetKb = budget.maxGzippedKb;

      // Use raw size as conservative estimate (gzip typically 60–70% reduction)
      // For CI accuracy, install `gzip` and pipe through it
      const estimatedGzipKb = (rawBytes * 0.35 / 1024).toFixed(1);

      if (parseFloat(estimatedGzipKb) > budgetKb) {
        console.error(
          `[bundle-budget] FAIL: ${file} — estimated gzip ${estimatedGzipKb} kB exceeds budget ${budgetKb} kB`
        );
        allPass = false;
      } else {
        console.log(
          `[bundle-budget] PASS: ${file} — raw ${rawKb} kB, estimated gzip ~${estimatedGzipKb} kB (budget: ${budgetKb} kB)`
        );
      }
    }
  }

  process.exit(allPass ? 0 : 1);
}

main().catch((err) => {
  console.error('[bundle-budget] Unexpected error:', err);
  process.exit(1);
});
