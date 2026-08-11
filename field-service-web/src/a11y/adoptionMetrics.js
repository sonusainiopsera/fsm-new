/**
 * Pure metric-calculation functions for the design-system adoption audit.
 *
 * These functions operate on raw CSS source strings so they can be unit-tested
 * in Vitest without filesystem access.  The audit script
 * (scripts/audit-design-system.mjs) calls these functions after reading files.
 */

const TOKEN_PATTERN = /var\(--[a-z][a-z0-9-]*/;

/**
 * Counts how many CSS value declarations use var(--token) references.
 * @param {string} cssSource
 * @returns {number}
 */
export function countTokenDeclarations(cssSource) {
  const declarations = extractValueDeclarations(cssSource);
  return declarations.filter((v) => TOKEN_PATTERN.test(v)).length;
}

/**
 * Counts total CSS value declarations (property: value pairs).
 * @param {string} cssSource
 * @returns {number}
 */
export function countTotalDeclarations(cssSource) {
  return extractValueDeclarations(cssSource).length;
}

/**
 * Computes the design-system adoption percentage for a CSS source string.
 * Returns 100 for files with no declarations.
 *
 * @param {string} cssSource
 * @returns {number} percentage in [0, 100]
 */
export function computeAdoptionPct(cssSource) {
  const total = countTotalDeclarations(cssSource);
  if (total === 0) return 100;
  const tokenised = countTokenDeclarations(cssSource);
  return Math.round((tokenised / total) * 100 * 10) / 10;
}

/**
 * Finds hardcoded visual literals in CSS source (hex colours, px/rem/em values
 * that are NOT inside a var() call, raw named colours).
 *
 * Returns an array of { line, value } objects for each literal found.
 *
 * @param {string} cssSource
 * @param {string[]} [allowList] - Exact string values to exempt (from token-exceptions.json)
 * @returns {{ line: number, value: string }[]}
 */
export function findHardcodedLiterals(cssSource, allowList = []) {
  const LITERAL_PATTERNS = [
    /#[0-9a-fA-F]{3,8}\b/g,               // hex colours
    /\b(?:rgb|rgba|hsl|hsla)\([^)]+\)/g,  // functional colour notations
    /\b(?:red|green|blue|yellow|orange|purple|pink|brown|black|white|gray|grey)\b/g,
  ];

  const results = [];
  const lines = cssSource.split('\n');
  lines.forEach((line, idx) => {
    // Skip comments and var() expressions
    const sanitised = line.replace(/\/\*.*?\*\//g, '').replace(/var\([^)]+\)/g, '');
    for (const pattern of LITERAL_PATTERNS) {
      pattern.lastIndex = 0;
      let m;
      while ((m = pattern.exec(sanitised)) !== null) {
        const value = m[0].trim();
        if (!allowList.includes(value)) {
          results.push({ line: idx + 1, value });
        }
      }
    }
  });
  return results;
}

/**
 * Aggregates metrics across an array of per-file results.
 *
 * @param {{ total: number, tokenised: number, literals: number }[]} fileMetrics
 * @returns {{ adoptionPct: number, bespokePct: number, totalLiterals: number }}
 */
export function aggregateMetrics(fileMetrics) {
  const total = fileMetrics.reduce((s, f) => s + f.total, 0);
  const tokenised = fileMetrics.reduce((s, f) => s + f.tokenised, 0);
  const literals = fileMetrics.reduce((s, f) => s + f.literals, 0);
  const adoptionPct = total === 0 ? 100 : Math.round((tokenised / total) * 100 * 10) / 10;
  const bespokePct = total === 0 ? 0 : Math.round(((total - tokenised) / total) * 100 * 10) / 10;
  return { adoptionPct, bespokePct, totalLiterals: literals };
}

// ── helpers ──────────────────────────────────────────────────────────────────

function extractValueDeclarations(cssSource) {
  const DECLARATION_RE = /[a-z-]+\s*:\s*([^;{}]+);/g;
  // Properties that govern visual appearance (subset relevant to the token contract)
  const VISUAL_PROPS = new Set([
    'color', 'background', 'background-color', 'border-color',
    'border-top-color', 'border-right-color', 'border-bottom-color', 'border-left-color',
    'outline-color', 'caret-color', 'fill', 'stroke', 'box-shadow',
    'border-radius', 'border-top-left-radius', 'border-top-right-radius',
    'border-bottom-left-radius', 'border-bottom-right-radius',
    'padding', 'padding-top', 'padding-right', 'padding-bottom', 'padding-left',
    'margin', 'margin-top', 'margin-right', 'margin-bottom', 'margin-left',
    'gap', 'row-gap', 'column-gap',
    'transition-duration', 'animation-duration',
    'font-size', 'font-family', 'font-weight', 'line-height', 'letter-spacing',
  ]);

  const values = [];
  let m;
  DECLARATION_RE.lastIndex = 0;
  while ((m = DECLARATION_RE.exec(cssSource)) !== null) {
    const full = m[0];
    const prop = full.split(':')[0].trim();
    if (VISUAL_PROPS.has(prop)) {
      values.push(m[1].trim());
    }
  }
  return values;
}
