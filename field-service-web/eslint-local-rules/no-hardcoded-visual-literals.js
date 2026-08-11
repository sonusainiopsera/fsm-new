'use strict';

/**
 * ESLint rule: no-hardcoded-visual-literals
 *
 * Rejects hardcoded colour, radius, spacing and duration values in JSX style
 * props and template literals, requiring design token references instead.
 *
 * Compliant:   style={{ color: 'var(--color-text-primary)' }}
 * Violation:   style={{ color: '#ff0000' }}
 * Violation:   style={{ borderRadius: '6px' }}
 * Violation:   style={{ padding: '16px' }}
 * Violation:   style={{ transitionDuration: '120ms' }}
 */

/** Properties that carry colour values */
const COLOR_PROPS = new Set([
  'color', 'backgroundColor', 'background', 'borderColor',
  'borderTopColor', 'borderRightColor', 'borderBottomColor', 'borderLeftColor',
  'outlineColor', 'caretColor', 'textDecorationColor', 'columnRuleColor',
  'fill', 'stroke',
]);

/** Properties that carry radius values */
const RADIUS_PROPS = new Set([
  'borderRadius',
  'borderTopLeftRadius', 'borderTopRightRadius',
  'borderBottomLeftRadius', 'borderBottomRightRadius',
]);

/** Properties that carry spacing values */
const SPACING_PROPS = new Set([
  'padding', 'paddingTop', 'paddingRight', 'paddingBottom', 'paddingLeft',
  'margin', 'marginTop', 'marginRight', 'marginBottom', 'marginLeft',
  'gap', 'rowGap', 'columnGap',
  'width', 'height', 'minWidth', 'maxWidth', 'minHeight', 'maxHeight',
  'top', 'right', 'bottom', 'left',
]);

/** Properties that carry duration values */
const DURATION_PROPS = new Set([
  'transitionDuration', 'animationDuration', 'transition', 'animation',
]);

// Pattern: hex colour, rgb/rgba/hsl/hsla function, or CSS named colour keywords
const HEX_PATTERN = /^#([0-9a-fA-F]{3,4}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/;
const COLOR_FUNC_PATTERN = /^(rgb|rgba|hsl|hsla|oklch|lch|lab|color)\s*\(/i;
const NAMED_COLORS = new Set([
  'aqua','black','blue','fuchsia','gray','green','lime','maroon','navy',
  'olive','orange','purple','red','silver','teal','white','yellow',
  'aliceblue','antiquewhite','coral','crimson','cyan','darkblue','darkgray',
  'darkgreen','darkorange','deepskyblue','dimgray','dodgerblue','firebrick',
  'forestgreen','gold','hotpink','indigo','ivory','khaki','lavender',
  'lightblue','lightgray','lightgreen','lightyellow','magenta','mediumblue',
  'mediumseagreen','mediumslateblue','mistyrose','moccasin','orange',
  'orangered','orchid','papayawhip','peachpuff','peru','pink','plum',
  'powderblue','rosybrown','royalblue','saddlebrown','salmon','sandybrown',
  'seagreen','seashell','sienna','skyblue','slateblue','slategray','snow',
  'springgreen','steelblue','tan','thistle','tomato','turquoise','violet',
  'wheat','yellowgreen',
]);

// Pattern: bare px values (single or multi-part spacing)
const PX_VALUE_PATTERN = /^\d+(\.\d+)?px(\s+\d+(\.\d+)?px)*$/;
// Pattern: bare ms/s duration
const MS_DURATION_PATTERN = /^\d+(\.\d+)?(ms|s)$/;

/**
 * @param {string} value
 * @returns {boolean}
 */
function isHardcodedColor(value) {
  const v = value.trim();
  return (
    HEX_PATTERN.test(v) ||
    COLOR_FUNC_PATTERN.test(v) ||
    NAMED_COLORS.has(v.toLowerCase())
  );
}

/**
 * @param {string} value
 * @returns {boolean}
 */
function isHardcodedPx(value) {
  return PX_VALUE_PATTERN.test(value.trim());
}

/**
 * @param {string} value
 * @returns {boolean}
 */
function isHardcodedDuration(value) {
  return MS_DURATION_PATTERN.test(value.trim());
}

/**
 * Returns true if the value is a token reference (var(--...)) or a safe keyword.
 * @param {string} value
 */
function isTokenRef(value) {
  return (
    value.trim().startsWith('var(--') ||
    value === 'inherit' ||
    value === 'initial' ||
    value === 'unset' ||
    value === 'transparent' ||
    value === 'currentColor' ||
    value === 'none' ||
    value === 'auto' ||
    value === '0'
  );
}

/** @type {import('eslint').Rule.RuleModule} */
module.exports = {
  meta: {
    type: 'problem',
    docs: {
      description: 'Disallow hardcoded visual literal values in JSX style props',
      url: 'docs/DESIGN_TOKENS.md',
    },
    messages: {
      hardcodedColor:
        'Hardcoded colour "{{value}}" — use a design token: var(--color-*).',
      hardcodedRadius:
        'Hardcoded radius "{{value}}" — use a design token: var(--radius-*).',
      hardcodedSpacing:
        'Hardcoded spacing "{{value}}" — use a design token: var(--space-*).',
      hardcodedDuration:
        'Hardcoded duration "{{value}}" — use a design token: var(--duration-*).',
    },
    schema: [],
  },

  create(context) {
    /**
     * Check a single style property/value pair.
     * @param {import('eslint').Rule.Node} node  AST node to report on
     * @param {string} propName  camelCase property name
     * @param {string} rawValue  string value
     */
    function checkProp(node, propName, rawValue) {
      if (isTokenRef(rawValue)) return;

      if (COLOR_PROPS.has(propName) && isHardcodedColor(rawValue)) {
        context.report({ node, messageId: 'hardcodedColor', data: { value: rawValue } });
      } else if (RADIUS_PROPS.has(propName) && isHardcodedPx(rawValue)) {
        context.report({ node, messageId: 'hardcodedRadius', data: { value: rawValue } });
      } else if (SPACING_PROPS.has(propName) && isHardcodedPx(rawValue)) {
        context.report({ node, messageId: 'hardcodedSpacing', data: { value: rawValue } });
      } else if (DURATION_PROPS.has(propName) && isHardcodedDuration(rawValue)) {
        context.report({ node, messageId: 'hardcodedDuration', data: { value: rawValue } });
      }
    }

    /**
     * Extract a string value from a Literal or no-expression TemplateLiteral node.
     * Returns null if the value cannot be statically determined.
     * @param {import('eslint').Rule.Node} node
     * @returns {string|null}
     */
    function extractStringValue(node) {
      if (node.type === 'Literal' && typeof node.value === 'string') {
        return node.value;
      }
      if (node.type === 'TemplateLiteral' && node.expressions.length === 0) {
        return node.quasis[0].value.cooked;
      }
      return null;
    }

    return {
      JSXAttribute(node) {
        // Only interested in the `style` attribute
        if (
          node.name.type !== 'JSXIdentifier' ||
          node.name.name !== 'style'
        ) {
          return;
        }

        if (!node.value || node.value.type !== 'JSXExpressionContainer') return;

        const expr = node.value.expression;
        if (expr.type !== 'ObjectExpression') return;

        for (const prop of expr.properties) {
          if (prop.type !== 'Property') continue;

          const keyName =
            prop.key.type === 'Identifier'
              ? prop.key.name
              : prop.key.type === 'Literal'
              ? String(prop.key.value)
              : null;
          if (!keyName) continue;

          const rawValue = extractStringValue(prop.value);
          if (rawValue == null) continue;

          checkProp(prop.value, keyName, rawValue);
        }
      },
    };
  },
};
