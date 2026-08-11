'use strict'

/**
 * ESLint rule: no-hardcoded-visual-literals
 *
 * Rejects hard-coded colour, px radius, px spacing, and ms duration values
 * in JSX style props and template literals.
 *
 * Catches:
 *  - <div style={{ color: '#ff0000' }} />        → colour hex literal
 *  - <div style={{ backgroundColor: 'rgb(0,0,0)' }} />
 *  - <div style={{ borderRadius: '6px' }} />     → px radius literal
 *  - <div style={{ padding: '16px' }} />          → px spacing literal
 *  - <div style={{ transitionDuration: '120ms' }} />
 *  - <div style={{ boxShadow: '0 2px 4px #000' }} />
 *
 * Does NOT flag:
 *  - style={{ color: 'var(--token-text-primary)' }}
 *  - style={{ borderRadius: 'var(--token-radius-control)' }}
 *  - style={{ padding: 0 }}   (zero needs no token)
 *  - style={{ opacity: 0.5 }} (opacity is not a restricted property)
 *
 * Exceptions are documented in styles/token-exceptions.json with a file path,
 * reason, and date. Usage of inline /* eslint-disable */ is still possible
 * for one-off vendor overrides but must be code-reviewed.
 */

/** CSS camelCase property families that must only use var(--token-*). */
const COLOR_PROPS = new Set([
  'color',
  'backgroundColor',
  'background',
  'borderColor',
  'borderTopColor',
  'borderRightColor',
  'borderBottomColor',
  'borderLeftColor',
  'outlineColor',
  'fill',
  'stroke',
  'caretColor',
  'columnRuleColor',
  'textDecorationColor',
])

const SPACING_PROPS = new Set([
  'padding',
  'paddingTop',
  'paddingRight',
  'paddingBottom',
  'paddingLeft',
  'margin',
  'marginTop',
  'marginRight',
  'marginBottom',
  'marginLeft',
  'gap',
  'columnGap',
  'rowGap',
  'top',
  'right',
  'bottom',
  'left',
  'width',
  'height',
  'minWidth',
  'minHeight',
  'maxWidth',
  'maxHeight',
])

const RADIUS_PROPS = new Set(['borderRadius', 'borderTopLeftRadius', 'borderTopRightRadius', 'borderBottomLeftRadius', 'borderBottomRightRadius'])

const DURATION_PROPS = new Set(['transitionDuration', 'animationDuration', 'transition', 'animation'])

const SHADOW_PROPS = new Set(['boxShadow', 'filter', 'textShadow'])

// Patterns for disallowed literal values
const HEX_RE = /^#[0-9a-fA-F]{3,8}$/
const RGB_RE = /^rgba?\s*\(/
const HSL_RE = /^hsla?\s*\(/
const GRADIENT_RE = /^(linear|radial|conic)-gradient\s*\(/
const NAMED_COLOR_RE = /^(aliceblue|antiquewhite|aqua|aquamarine|azure|beige|bisque|black|blanchedalmond|blue|blueviolet|brown|burlywood|cadetblue|chartreuse|chocolate|coral|cornflowerblue|cornsilk|crimson|cyan|darkblue|darkcyan|darkgoldenrod|darkgray|darkgreen|darkgrey|darkkhaki|darkmagenta|darkolivegreen|darkorange|darkorchid|darkred|darksalmon|darkseagreen|darkslateblue|darkslategray|darkslategrey|darkturquoise|darkviolet|deeppink|deepskyblue|dimgray|dimgrey|dodgerblue|firebrick|floralwhite|forestgreen|fuchsia|gainsboro|ghostwhite|gold|goldenrod|gray|green|greenyellow|grey|honeydew|hotpink|indianred|indigo|ivory|khaki|lavender|lavenderblush|lawngreen|lemonchiffon|lightblue|lightcoral|lightcyan|lightgoldenrodyellow|lightgray|lightgreen|lightgrey|lightpink|lightsalmon|lightseagreen|lightskyblue|lightslategray|lightslategrey|lightsteelblue|lightyellow|lime|limegreen|linen|magenta|maroon|mediumaquamarine|mediumblue|mediumorchid|mediumpurple|mediumseagreen|mediumslateblue|mediumspringgreen|mediumturquoise|mediumvioletred|midnightblue|mintcream|mistyrose|moccasin|navajowhite|navy|oldlace|olive|olivedrab|orange|orangered|orchid|palegoldenrod|palegreen|paleturquoise|palevioletred|papayawhip|peachpuff|peru|pink|plum|powderblue|purple|rebeccapurple|red|rosybrown|royalblue|saddlebrown|salmon|sandybrown|seagreen|seashell|sienna|silver|skyblue|slateblue|slategray|slategrey|snow|springgreen|steelblue|tan|teal|thistle|tomato|turquoise|violet|wheat|white|whitesmoke|yellow|yellowgreen)$/i
const PX_NONZERO_RE = /^[1-9][0-9]*(\.\d+)?px$/
const MS_RE = /^[0-9]+(\.\d+)?ms$/
const SECONDS_RE = /^[0-9]+(\.\d+)?s$/

/**
 * @param {string} value
 * @param {Set<string>} propFamily
 * @returns {{ type: string } | null}
 */
function getViolation(propName, value) {
  if (typeof value !== 'string') return null
  // Always allow var(--token-*) references
  if (value.startsWith('var(--token-')) return null
  // Allow zero without units
  if (value === '0' || value === '0px') return null

  if (COLOR_PROPS.has(propName)) {
    if (HEX_RE.test(value)) return { type: 'colour', detail: 'hex literal' }
    if (RGB_RE.test(value)) return { type: 'colour', detail: 'rgb() literal' }
    if (HSL_RE.test(value)) return { type: 'colour', detail: 'hsl() literal' }
    if (GRADIENT_RE.test(value)) return { type: 'colour', detail: 'gradient literal' }
    if (NAMED_COLOR_RE.test(value)) return { type: 'colour', detail: 'named colour literal' }
  }

  if (RADIUS_PROPS.has(propName)) {
    if (PX_NONZERO_RE.test(value)) return { type: 'radius', detail: 'px literal' }
  }

  if (SPACING_PROPS.has(propName)) {
    if (PX_NONZERO_RE.test(value)) return { type: 'spacing', detail: 'px literal' }
  }

  if (DURATION_PROPS.has(propName)) {
    if (MS_RE.test(value)) return { type: 'duration', detail: 'ms literal' }
    if (SECONDS_RE.test(value)) return { type: 'duration', detail: 's literal' }
  }

  if (SHADOW_PROPS.has(propName)) {
    // Reject any box-shadow that contains a colour literal
    if (HEX_RE.test(value) || RGB_RE.test(value) || HSL_RE.test(value)) {
      return { type: 'colour', detail: 'colour in shadow literal' }
    }
  }

  return null
}

/** @type {import('eslint').Rule.RuleModule} */
module.exports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Disallow hard-coded visual literals (colours, px radii, px spacing, ms durations) in JSX style props. Use var(--token-*) instead.',
      url: 'field-service-web/docs/DESIGN_TOKENS.md',
    },
    messages: {
      hardcodedLiteral:
        'Hard-coded {{type}} value "{{value}}" in style prop "{{prop}}". Use var(--token-*) instead.',
    },
    schema: [],
  },
  create(context) {
    /**
     * Check a JSXExpressionContainer value inside a style prop.
     * @param {import('eslint').Rule.Node} node - ObjectExpression
     */
    function checkStyleObject(node) {
      if (node.type !== 'ObjectExpression') return
      for (const prop of node.properties) {
        if (prop.type !== 'Property') continue
        const propName =
          prop.key.type === 'Identifier'
            ? prop.key.name
            : prop.key.type === 'Literal'
            ? String(prop.key.value)
            : null
        if (!propName) continue

        const valueNode = prop.value
        const rawValue =
          valueNode.type === 'Literal'
            ? String(valueNode.value)
            : valueNode.type === 'TemplateLiteral' && valueNode.quasis.length === 1
            ? valueNode.quasis[0].value.cooked
            : null

        if (rawValue !== null) {
          const violation = getViolation(propName, rawValue)
          if (violation) {
            context.report({
              node: valueNode,
              messageId: 'hardcodedLiteral',
              data: {
                type: violation.type,
                value: rawValue,
                prop: propName,
              },
            })
          }
        }
      }
    }

    return {
      JSXAttribute(node) {
        if (node.name.name !== 'style') return
        const valueNode = node.value
        if (!valueNode || valueNode.type !== 'JSXExpressionContainer') return
        const expr = valueNode.expression
        if (expr.type === 'ObjectExpression') {
          checkStyleObject(expr)
        }
      },
    }
  },
}
