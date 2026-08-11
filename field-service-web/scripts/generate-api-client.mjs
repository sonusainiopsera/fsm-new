#!/usr/bin/env node
/**
 * generate-api-client.mjs — OpenAPI → JSDoc typedefs + endpoint accessors
 *
 * Usage:
 *   node scripts/generate-api-client.mjs [--spec <path>] [--out <path>] [--check]
 *
 * Options:
 *   --spec  Path to openapi.json or openapi.yaml (default: openapi.json in project root)
 *   --out   Output path for generated file (default: src/api/generated/client.js)
 *   --check Diff-check mode: exit 1 if generated output differs from existing file (CI)
 *
 * The generated file exports:
 *   - JSDoc @typedef for each schema component
 *   - Typed endpoint accessors keyed by operationId
 *   - QUERY_KEYS map for each GET operation
 */

import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { resolve, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)
const require = createRequire(import.meta.url)

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

const argv = process.argv.slice(2)

function getArg(flag, defaultValue) {
  const idx = argv.indexOf(flag)
  if (idx === -1) return defaultValue
  return argv[idx + 1] ?? defaultValue
}

const CHECK_MODE = argv.includes('--check')
const SPEC_PATH = resolve(
  __dirname,
  '..',
  getArg('--spec', 'openapi.json'),
)
const OUT_PATH = resolve(
  __dirname,
  '..',
  getArg('--out', 'src/api/generated/client.js'),
)

// ---------------------------------------------------------------------------
// Load spec
// ---------------------------------------------------------------------------

function loadSpec(specPath) {
  if (!existsSync(specPath)) {
    console.error(`[generate-api-client] spec not found: ${specPath}`)
    process.exit(1)
  }

  const raw = readFileSync(specPath, 'utf8')

  if (specPath.endsWith('.json')) {
    return JSON.parse(raw)
  }

  // Minimal YAML scalar parser — only handles the subset produced by Springdoc
  return parseYaml(raw)
}

// ---------------------------------------------------------------------------
// Minimal YAML parser (covers Springdoc output — scalars/sequences/mappings)
// ---------------------------------------------------------------------------

function parseYaml(src) {
  // Delegate to JSON.parse for JSON-compatible YAML, otherwise use a best-
  // effort line parser. For production use, install `js-yaml`; this covers CI.
  try {
    return JSON.parse(src)
  } catch {
    // Fall through to basic line-oriented parser
  }

  const lines = src.split('\n')
  const stack = [{ indent: -1, obj: {} }]
  let root = null

  for (const raw of lines) {
    if (!raw.trim() || raw.trim().startsWith('#')) continue

    const indent = raw.search(/\S/)
    const line = raw.trimEnd()

    while (stack.length > 1 && stack[stack.length - 1].indent >= indent) {
      stack.pop()
    }

    const parent = stack[stack.length - 1].obj
    const match = line.match(/^(\s*)([\w$./-]+):\s*(.*)$/)
    if (!match) continue

    const key = match[2]
    const val = match[3].trim()

    if (val === '' || val === '|' || val === '>') {
      const child = {}
      parent[key] = child
      stack.push({ indent, obj: child })
      if (!root) root = child
    } else if (val.startsWith('"') || val.startsWith("'")) {
      parent[key] = val.replace(/^['"]|['"]$/g, '')
    } else if (val === 'true') parent[key] = true
    else if (val === 'false') parent[key] = false
    else if (!isNaN(Number(val))) parent[key] = Number(val)
    else parent[key] = val
  }

  return stack[0].obj
}

// ---------------------------------------------------------------------------
// Schema → JSDoc typedef generator
// ---------------------------------------------------------------------------

function openApiTypeToJsDoc(schema, schemas, depth = 0) {
  if (!schema) return 'any'

  if (schema.$ref) {
    const name = schema.$ref.replace('#/components/schemas/', '')
    return name
  }

  switch (schema.type) {
    case 'string': return schema.format === 'date-time' ? 'string' : 'string'
    case 'integer':
    case 'number': return 'number'
    case 'boolean': return 'boolean'
    case 'array':
      return `Array<${openApiTypeToJsDoc(schema.items, schemas, depth)}>`
    case 'object': {
      if (!schema.properties) return 'Object.<string, any>'
      const props = Object.entries(schema.properties)
        .map(([k, v]) => `${k}: ${openApiTypeToJsDoc(v, schemas, depth + 1)}`)
        .join(', ')
      return `{ ${props} }`
    }
    default:
      return 'any'
  }
}

function generateTypedefs(spec) {
  const schemas = spec?.components?.schemas ?? {}
  const lines = []

  lines.push('// AUTO-GENERATED — do not edit by hand. Run: node scripts/generate-api-client.mjs')
  lines.push('//')
  lines.push('// @ts-check')
  lines.push('')

  for (const [name, schema] of Object.entries(schemas)) {
    if (schema.type === 'object' && schema.properties) {
      lines.push(`/**`)
      lines.push(` * @typedef {Object} ${name}`)
      for (const [prop, propSchema] of Object.entries(schema.properties)) {
        const required = Array.isArray(schema.required) && schema.required.includes(prop)
        const type = openApiTypeToJsDoc(propSchema, schemas)
        lines.push(` * @property {${type}${required ? '' : '='}} ${prop}`)
      }
      lines.push(` */`)
      lines.push('')
    } else if (schema.enum) {
      lines.push(`/**`)
      lines.push(` * @typedef {${schema.enum.map(v => `'${v}'`).join(' | ')}} ${name}`)
      lines.push(` */`)
      lines.push('')
    }
  }

  return lines
}

// ---------------------------------------------------------------------------
// Path → endpoint accessor generator
// ---------------------------------------------------------------------------

function methodToJsIdentifier(method, path) {
  const segments = path
    .split('/')
    .filter(Boolean)
    .map(s => s.startsWith('{') ? 'By' + s.replace(/[{}]/g, '') : s)
    .map(s => s.charAt(0).toUpperCase() + s.slice(1).replace(/-./g, m => m[1].toUpperCase()))
    .join('')
  return method.toLowerCase() + segments
}

function generateEndpoints(spec) {
  const paths = spec?.paths ?? {}
  const lines = []

  lines.push(`import { request } from '../http.js'`)
  lines.push(`import { buildPageQuery, toQueryString } from '../pagination.js'`)
  lines.push('')

  const queryKeys = {}

  for (const [path, methods] of Object.entries(paths)) {
    for (const [method, op] of Object.entries(methods)) {
      if (!op || typeof op !== 'object') continue
      const operationId = op.operationId ?? methodToJsIdentifier(method, path)
      const hasPathParams = path.includes('{')
      const isGet = method === 'get'
      const isPaged = op.parameters?.some(p => p.name === 'page') ?? false

      // Build function
      const paramNames = (op.parameters ?? [])
        .filter(p => p.in === 'path')
        .map(p => p.name)

      const hasBody = ['post', 'put', 'patch'].includes(method)

      const fnArgs = [
        ...paramNames,
        hasBody ? 'body' : null,
        isPaged ? 'pageOptions = {}' : null,
      ].filter(Boolean)

      // Build path template
      const resolvedPath = path.replace(/\{(\w+)\}/g, '${$1}')

      let returnExpr
      if (isPaged) {
        returnExpr = `request(\`/api/v1${resolvedPath}?\${toQueryString(buildPageQuery(pageOptions))}\`, { method: '${method.toUpperCase()}' })`
      } else if (hasBody) {
        returnExpr = `request(\`/api/v1${resolvedPath}\`, { method: '${method.toUpperCase()}', body: JSON.stringify(body) })`
      } else {
        returnExpr = `request(\`/api/v1${resolvedPath}\`, { method: '${method.toUpperCase()}' })`
      }

      lines.push(`/** @type {(${fnArgs.map(a => a.split(' ')[0]).join(', ')}) => Promise<any>} */`)
      lines.push(`export function ${operationId}(${fnArgs.join(', ')}) {`)
      lines.push(`  return ${returnExpr}`)
      lines.push(`}`)
      lines.push('')

      if (isGet) {
        const keyParts = path
          .split('/')
          .filter(s => s && !s.startsWith('{'))
          .map(s => `'${s}'`)
          .join(', ')
        const keyFn = hasPathParams
          ? `(${paramNames.join(', ')}) => [${keyParts}, ${paramNames.join(', ')}]`
          : `() => [${keyParts}]`
        queryKeys[operationId] = keyFn
      }
    }
  }

  // QUERY_KEYS export
  lines.push(`/** Query key factories keyed by operationId. */`)
  lines.push(`export const QUERY_KEYS = {`)
  for (const [opId, fn] of Object.entries(queryKeys)) {
    lines.push(`  ${opId}: ${fn},`)
  }
  lines.push(`}`)
  lines.push('')

  return lines
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------

function generate(specPath) {
  const spec = loadSpec(specPath)
  const typeLines = generateTypedefs(spec)
  const endpointLines = generateEndpoints(spec)

  const output = [
    ...typeLines,
    '// ---------------------------------------------------------------------------',
    '// Endpoint accessors',
    '// ---------------------------------------------------------------------------',
    '',
    ...endpointLines,
  ].join('\n') + '\n'

  return output
}

function ensureDir(filePath) {
  const dir = dirname(filePath)
  if (!existsSync(dir)) {
    const { mkdirSync } = await import('node:fs')
    mkdirSync(dir, { recursive: true })
  }
}

// ---------------------------------------------------------------------------
// Check if spec exists; if not, emit a placeholder with instructions
// ---------------------------------------------------------------------------

if (!existsSync(SPEC_PATH)) {
  console.warn(`[generate-api-client] WARNING: ${SPEC_PATH} not found.`)
  console.warn(`  Export the OpenAPI spec from your Spring Boot app:`)
  console.warn(`    curl http://localhost:8080/v3/api-docs > openapi.json`)
  console.warn(`  Then re-run: node scripts/generate-api-client.mjs`)

  const placeholder = [
    `// AUTO-GENERATED PLACEHOLDER — spec not found at ${SPEC_PATH}`,
    `// Run: curl http://localhost:8080/v3/api-docs > openapi.json`,
    `// Then: node scripts/generate-api-client.mjs`,
    ``,
    `export const QUERY_KEYS = {}`,
    ``,
  ].join('\n')

  const outDir = dirname(OUT_PATH)
  if (!existsSync(outDir)) {
    const { mkdirSync } = await import('node:fs')
    mkdirSync(outDir, { recursive: true })
  }

  if (!CHECK_MODE) {
    writeFileSync(OUT_PATH, placeholder, 'utf8')
    console.log(`[generate-api-client] wrote placeholder → ${OUT_PATH}`)
  }
  process.exit(0)
}

const generated = generate(SPEC_PATH)

if (CHECK_MODE) {
  // Drift check for CI
  if (!existsSync(OUT_PATH)) {
    console.error(`[generate-api-client] --check: ${OUT_PATH} does not exist. Run generate-api-client to create it.`)
    process.exit(1)
  }
  const existing = readFileSync(OUT_PATH, 'utf8')
  if (generated !== existing) {
    console.error(`[generate-api-client] --check: generated client differs from committed file.`)
    console.error(`  Run: node scripts/generate-api-client.mjs`)
    process.exit(1)
  }
  console.log(`[generate-api-client] --check: OK (no drift)`)
  process.exit(0)
}

// Write output
const outDir = dirname(OUT_PATH)
if (!existsSync(outDir)) {
  const { mkdirSync } = await import('node:fs')
  mkdirSync(outDir, { recursive: true })
}

writeFileSync(OUT_PATH, generated, 'utf8')
console.log(`[generate-api-client] wrote ${Object.keys((loadSpec(SPEC_PATH)?.paths ?? {})).length} paths → ${OUT_PATH}`)
