'use strict';

/**
 * no-hardcoded-visual-literals
 *
 * Rejects hard-coded colour, px-radius, px-spacing and ms-duration
 * literal values in JSX `style` prop objects and template literals.
 *
 * Rationale: The only permitted values are var(--fs-*) references,
 * CSS keywords (inherit, currentColor, transparent, auto) and 0.
 * This mirrors the Stylelint enforcement applied to .css files but
 * operates on JSX/JS source so runtime-injected styles obey the
 * same contract.
 *
 * Loaded via: eslint src --rulesdir eslint-local-rules
 */

/** Properties whose values must reference design tokens. */
const VISUAL_PROPS = new Set([
  'color',
  'backgroundColor',
  'background',
  'borderColor',
  'borderTopColor',
  'borderRightColor',
  'borderBottomColor',
  'borderLeftColor',
  'outlineColor',
  'borderRadius',
  'borderTopLeftRadius',
  'borderTopRightRadius',
  'borderBottomLeftRadius',
  'borderBottomRightRadius',
  'padding',
  'paddingTop',
  'paddingRight',
  'paddingBottom',
  'paddingLeft',
  'paddingInline',
  'paddingBlock',
  'margin',
  'marginTop',
  'marginRight',
  'marginBottom',
  'marginLeft',
  'marginInline',
  'marginBlock',
  'gap',
  'rowGap',
  'columnGap',
  'boxShadow',
  'transitionDuration',
  'animationDuration',
]);

/** CSS keywords that are always permitted regardless of property. */
const ALLOWED_KEYWORDS = new Set([
  'inherit',
  'initial',
  'unset',
  'revert',
  'currentColor',
  'transparent',
  'auto',
  'none',
  '0',
]);

const HEX_RE = /^#[0-9a-fA-F]{3,8}$/;
const RGB_RE = /^rgba?\s*\(/;
const HSL_RE = /^hsla?\s*\(/;
const NAMED_COLOUR_RE = /^(red|green|blue|yellow|orange|purple|pink|brown|black|white|gray|grey|lime|cyan|magenta|maroon|navy|olive|silver|teal|aqua|fuchsia)\b/i;
const PX_RE = /^\d+(\.\d+)?px$/;
const MS_RE = /^\d+(\.\d+)?ms$/;
const VAR_RE = /^var\s*\(--/;

function isHardcodedVisual(/** @type {string} */ value) {
  if (ALLOWED_KEYWORDS.has(value)) return false;
  if (VAR_RE.test(value)) return false;
  return (
    HEX_RE.test(value) ||
    RGB_RE.test(value) ||
    HSL_RE.test(value) ||
    NAMED_COLOUR_RE.test(value) ||
    PX_RE.test(value) ||
    MS_RE.test(value)
  );
}

/** @type {import('eslint').Rule.RuleModule} */
module.exports = {
  meta: {
    type: 'problem',
    docs: {
      description:
        'Reject hard-coded colour, radius, spacing and duration literals in JSX style props. Use var(--fs-*) design tokens instead.',
      url: 'file:eslint-local-rules/no-hardcoded-visual-literals.js',
    },
    messages: {
      noHardcodedVisual:
        "Hard-coded visual literal '{{value}}' in style prop. Use a CSS custom property: var(--fs-*).",
    },
    schema: [],
  },

  create(context) {
    function checkObjectExpression(objExpr) {
      for (const prop of objExpr.properties) {
        if (prop.type !== 'Property') continue;

        const propName =
          prop.key.type === 'Identifier'
            ? prop.key.name
            : prop.key.type === 'Literal'
            ? String(prop.key.value)
            : null;

        if (!propName || !VISUAL_PROPS.has(propName)) continue;

        const val = prop.value;

        if (val.type === 'Literal' && typeof val.value === 'string') {
          if (isHardcodedVisual(val.value)) {
            context.report({
              node: val,
              messageId: 'noHardcodedVisual',
              data: { value: val.value },
            });
          }
        }

        if (val.type === 'TemplateLiteral') {
          const raw = val.quasis.map((q) => q.value.raw).join('${…}');
          if (HEX_RE.test(raw) || RGB_RE.test(raw) || HSL_RE.test(raw)) {
            context.report({
              node: val,
              messageId: 'noHardcodedVisual',
              data: { value: raw },
            });
          }
        }
      }
    }

    return {
      JSXAttribute(node) {
        if (!node.name || node.name.name !== 'style') return;
        if (!node.value || node.value.type !== 'JSXExpressionContainer') return;

        const expr = node.value.expression;
        if (expr.type === 'ObjectExpression') {
          checkObjectExpression(expr);
        }
      },
    };
  },
};
