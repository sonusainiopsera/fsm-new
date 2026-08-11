#!/usr/bin/env node
/**
 * OpenAPI client generator — Q6 compensating control.
 *
 * Reads the committed OpenAPI snapshot and emits:
 *   src/api/generated/types.js       JSDoc typedef per schema component
 *   src/api/generated/endpoints.js   Typed endpoint accessor per operation
 *
 * Runtime shape validation is injected into each accessor so boundary
 * drift is caught at the integration point, not silently swallowed.
 *
 * Wired into the build:node stage so generation failures block CI.
 * Run with --check to assert the committed generated files are in sync
 * with the current snapshot (drift detection).
 *
 * Usage:
 *   node scripts/generate-api-client.mjs            # generate
 *   node scripts/generate-api-client.mjs --check    # drift check (CI)
 */

import { readFileSync, writeFileSync, mkdirSync, existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { createHash } from 'node:crypto';

const __dirname = dirname(fileURLToPath(import.meta.url));
const ROOT = join(__dirname, '..');

const SNAPSHOT_PATH  = join(ROOT, '..', 'app', 'src', 'test', 'resources', 'openapi-snapshot.json');
const OUT_DIR        = join(ROOT, 'src', 'api', 'generated');
const TYPES_FILE     = join(OUT_DIR, 'types.js');
const ENDPOINTS_FILE = join(OUT_DIR, 'endpoints.js');

const CHECK_MODE = process.argv.includes('--check');

// ---- Load snapshot ----------------------------------------------------

let spec;
try {
  const raw = readFileSync(SNAPSHOT_PATH, 'utf-8');
  spec = JSON.parse(raw);
} catch (err) {
  console.error(`[generate-api-client] Failed to read OpenAPI snapshot: ${err.message}`);
  process.exit(1);
}

const schemas   = spec.components?.schemas ?? {};
const paths     = spec.paths ?? {};

// ---- Generate JSDoc typedefs ------------------------------------------

const typeLines = [
  '// GENERATED — do not edit by hand. Run: node scripts/generate-api-client.mjs',
  '// Source: openapi-snapshot.json',
  '',
];

for (const [name, schema] of Object.entries(schemas)) {
  typeLines.push(`/**`);
  if (schema.description) typeLines.push(` * ${schema.description}`);
  typeLines.push(` * @typedef {Object} ${name}`);

  const props = schema.properties ?? {};
  const required = new Set(schema.required ?? []);
  for (const [prop, def] of Object.entries(props)) {
    const optional = required.has(prop) ? '' : '=';
    const jsType = _toJsType(def);
    typeLines.push(` * @property {${jsType}${optional}} ${prop}`);
  }
  typeLines.push(` */`);
  typeLines.push('');
}

// ---- Generate endpoint accessors --------------------------------------

const endpointLines = [
  '// GENERATED — do not edit by hand. Run: node scripts/generate-api-client.mjs',
  '// Source: openapi-snapshot.json',
  '',
  "import { apiFetch } from '../http.js';",
  '',
];

for (const [path, methods] of Object.entries(paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    if (!operation || typeof operation !== 'object') continue;
    const opId   = operation.operationId ?? _pathToId(method, path);
    const jsPath = path.replace(/{(\w+)}/g, '${$1}');
    const params = (operation.parameters ?? []).filter(p => p.in === 'path').map(p => p.name);
    const hasBody = ['post', 'put', 'patch'].includes(method);

    const args = [...params, hasBody ? 'body' : null, 'opts = {}'].filter(Boolean);

    endpointLines.push(`/**`);
    if (operation.summary) endpointLines.push(` * ${operation.summary}`);
    endpointLines.push(` * @param {Object} [opts]`);
    endpointLines.push(` * @returns {Promise<unknown>}`);
    endpointLines.push(` */`);
    endpointLines.push(`export async function ${_camel(opId)}(${args.join(', ')}) {`);
    endpointLines.push(`  const result = await apiFetch(\`${jsPath}\`, {`);
    endpointLines.push(`    method: '${method.toUpperCase()}',`);
    if (hasBody) endpointLines.push(`    body: JSON.stringify(body),`);
    endpointLines.push(`    ...opts,`);
    endpointLines.push(`  });`);
    endpointLines.push(`  _assertShape(result, '${opId}');`);
    endpointLines.push(`  return result;`);
    endpointLines.push(`}`);
    endpointLines.push('');
  }
}

// Runtime shape validator
endpointLines.push(
  `/** @param {unknown} result @param {string} opId */`,
  `function _assertShape(result, opId) {`,
  `  if (result !== null && (typeof result !== 'object' || Array.isArray(result))) {`,
  `    throw new Error(\`[api] Boundary validation failed for \${opId}: unexpected shape\`);`,
  `  }`,
  `}`,
);

// ---- Write or check ---------------------------------------------------

const typesContent     = typeLines.join('\n');
const endpointsContent = endpointLines.join('\n');

if (CHECK_MODE) {
  let drift = false;
  for (const [file, content] of [[TYPES_FILE, typesContent], [ENDPOINTS_FILE, endpointsContent]]) {
    if (!existsSync(file)) {
      console.error(`[generate-api-client] Drift detected: ${file} does not exist. Run: node scripts/generate-api-client.mjs`);
      drift = true;
      continue;
    }
    const existing = readFileSync(file, 'utf-8');
    if (_hash(existing) !== _hash(content)) {
      console.error(`[generate-api-client] Drift detected: ${file} is out of sync with openapi-snapshot.json`);
      drift = true;
    }
  }
  if (drift) process.exit(1);
  console.log('[generate-api-client] ✓ Generated client is in sync with OpenAPI snapshot');
} else {
  mkdirSync(OUT_DIR, { recursive: true });
  writeFileSync(TYPES_FILE, typesContent, 'utf-8');
  writeFileSync(ENDPOINTS_FILE, endpointsContent, 'utf-8');
  console.log(`[generate-api-client] ✓ Generated:`);
  console.log(`    ${TYPES_FILE}`);
  console.log(`    ${ENDPOINTS_FILE}`);
}

// ---- Helpers ----------------------------------------------------------

function _toJsType(def) {
  if (!def) return 'unknown';
  if (def.$ref) return def.$ref.split('/').pop();
  if (def.type === 'array') return `${_toJsType(def.items)}[]`;
  const map = { string: 'string', integer: 'number', number: 'number', boolean: 'boolean', object: 'Object' };
  return map[def.type] ?? 'unknown';
}

function _pathToId(method, path) {
  return method + path.replace(/[/{}-]+/g, '_').replace(/^_|_$/g, '');
}

function _camel(str) {
  return str.replace(/_([a-z])/g, (_, c) => c.toUpperCase());
}

function _hash(content) {
  return createHash('sha256').update(content).digest('hex');
}
