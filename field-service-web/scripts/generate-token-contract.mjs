/**
 * Generate tokens.contract.json from tokens.contract.css.
 *
 * Parses every CSS custom property declaration matching `--token-*`
 * in the contract stylesheet and emits a machine-readable JSON mirror
 * consumed by the parity unit test and the custom ESLint rule.
 *
 * Run automatically via `npm run prebuild`.
 * Also runnable manually: `node scripts/generate-token-contract.mjs`
 */
import { readFileSync, writeFileSync } from 'fs'
import { fileURLToPath } from 'url'
import { dirname, join } from 'path'

const __filename = fileURLToPath(import.meta.url)
const __dirname = dirname(__filename)

const contractCssPath = join(__dirname, '../src/styles/tokens.contract.css')
const contractJsonPath = join(__dirname, '../src/styles/tokens.contract.json')

const css = readFileSync(contractCssPath, 'utf-8')

// Extract all --token-* custom property names.
// Matches `--token-foo-bar` followed by optional whitespace and a colon.
const tokenRegex = /(--token-[\w-]+)\s*:/g
const tokenNames = []
const seen = new Set()
let match

while ((match = tokenRegex.exec(css)) !== null) {
  const name = match[1]
  if (!seen.has(name)) {
    seen.add(name)
    tokenNames.push(name)
  }
}

if (tokenNames.length === 0) {
  console.error('ERROR: No --token-* properties found in tokens.contract.css')
  process.exit(1)
}

/**
 * Derive semantic families from the token list.
 * A semantic family is any prefix in --token-{family}-{variant}.
 * Excludes "neutral" and "accent" which are ramps, not semantic families.
 */
const EXCLUDED_PREFIXES = new Set(['neutral', 'accent', 'surface', 'text', 'border'])
const semanticFamilySet = new Set()
for (const name of tokenNames) {
  // --token-{family}-{variant}
  const parts = name.replace('--token-', '').split('-')
  if (parts.length >= 2) {
    const family = parts[0]
    if (!EXCLUDED_PREFIXES.has(family)) {
      const semanticFamilies = ['info', 'success', 'warning', 'danger']
      if (semanticFamilies.includes(family)) {
        semanticFamilySet.add(family)
      }
    }
  }
}

const contract = {
  $schema: 'https://json-schema.org/draft/2020-12/schema',
  version: '1',
  generatedFrom: 'src/styles/tokens.contract.css',
  generatedAt: new Date().toISOString(),
  tokenCount: tokenNames.length,
  semanticFamilies: Array.from(semanticFamilySet),
  tokens: tokenNames,
}

writeFileSync(contractJsonPath, JSON.stringify(contract, null, 2) + '\n', 'utf-8')

console.log(
  `[generate-token-contract] Wrote ${tokenNames.length} tokens, ` +
  `${semanticFamilySet.size} semantic families → src/styles/tokens.contract.json`
)
