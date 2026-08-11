/**
 * WCAG 2.1 contrast ratio helpers.
 *
 * All functions operate on hex colour strings and are pure/side-effect-free
 * so they can be imported in Vitest unit tests without a browser.
 */

/**
 * Parses a CSS hex colour (#rrggbb or #rgb) to {r, g, b} in [0, 255].
 * @param {string} hex
 * @returns {{ r: number, g: number, b: number }}
 */
export function parseHex(hex) {
  const h = hex.replace(/^#/, '');
  if (h.length === 3) {
    return {
      r: parseInt(h[0] + h[0], 16),
      g: parseInt(h[1] + h[1], 16),
      b: parseInt(h[2] + h[2], 16),
    };
  }
  if (h.length !== 6) throw new Error(`parseHex: invalid hex colour "${hex}"`);
  return {
    r: parseInt(h.slice(0, 2), 16),
    g: parseInt(h.slice(2, 4), 16),
    b: parseInt(h.slice(4, 6), 16),
  };
}

/**
 * Computes the WCAG relative luminance of an sRGB colour in [0, 255].
 * @param {{ r: number, g: number, b: number }} rgb
 * @returns {number} luminance in [0, 1]
 */
export function relativeLuminance({ r, g, b }) {
  const linearise = (c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  };
  return 0.2126 * linearise(r) + 0.7152 * linearise(g) + 0.0722 * linearise(b);
}

/**
 * Computes the WCAG 2.1 contrast ratio between two hex colours.
 * Returns a value in [1, 21].
 *
 * @param {string} fgHex
 * @param {string} bgHex
 * @returns {number}
 */
export function contrastRatio(fgHex, bgHex) {
  const L1 = relativeLuminance(parseHex(fgHex));
  const L2 = relativeLuminance(parseHex(bgHex));
  const lighter = Math.max(L1, L2);
  const darker = Math.min(L1, L2);
  return (lighter + 0.05) / (darker + 0.05);
}

/**
 * @typedef {{ pass: boolean, ratio: number, required: number, pairingId: string }} ContrastResult
 */

/**
 * Asserts that fg/bg contrast meets the minimum ratio.
 * @param {string} fgHex
 * @param {string} bgHex
 * @param {number} minRatio
 * @param {string} [pairingId]
 * @returns {ContrastResult}
 */
export function assertContrast(fgHex, bgHex, minRatio, pairingId = 'unnamed') {
  const ratio = contrastRatio(fgHex, bgHex);
  return {
    pass: ratio >= minRatio,
    ratio: Math.round(ratio * 100) / 100,
    required: minRatio,
    pairingId,
  };
}

/**
 * Validates all pairings in a manifest against both light and dark value sets.
 * A pairing fails if the computed contrast ratio is below its declared minimum
 * in either appearance.  A missing fg/bg hex for an appearance skips that check.
 *
 * @param {object} manifest - Parsed contrastPairings.json
 * @returns {{ failures: object[] }}
 */
export function validatePairings(manifest) {
  const failures = [];
  for (const pairing of manifest.pairings) {
    for (const appearance of ['light', 'dark']) {
      const fg = pairing.fg[appearance];
      const bg = pairing.bg[appearance];
      if (!fg || !bg) continue;
      const result = assertContrast(fg, bg, pairing.minRatio, pairing.id);
      if (!result.pass) {
        failures.push({
          id: pairing.id,
          appearance,
          ratio: result.ratio,
          required: result.required,
          fg,
          bg,
        });
      }
    }
  }
  return { failures };
}

/**
 * Checks that every declared pairing for `persona` and `appearance` meets
 * the persona-specific minimum (technician requires 7:1 in light).
 *
 * @param {object} manifest - Parsed contrastPairings.json
 * @param {string} persona  - e.g. 'technician'
 * @param {'light'|'dark'} appearance
 * @returns {{ failures: object[] }}
 */
export function validatePersonaPairings(manifest, persona, appearance) {
  const failures = [];
  for (const pairing of manifest.pairings) {
    if (pairing.personas && !pairing.personas.includes(persona)) continue;
    const fg = pairing.fg[appearance];
    const bg = pairing.bg[appearance];
    if (!fg || !bg) continue;
    const result = assertContrast(fg, bg, pairing.minRatio, pairing.id);
    if (!result.pass) {
      failures.push({ ...result, appearance, persona });
    }
  }
  return { failures };
}

/**
 * Asserts that a DOM element carries both a visible text node and an
 * icon/shape element — the structural minimum for greyscale survivability
 * per BR-34.
 *
 * @param {Element} el - The status element to inspect
 * @returns {{ pass: boolean, missingText: boolean, missingIcon: boolean }}
 */
export function assertGreyscaleSurvivability(el) {
  const textNodes = [...el.childNodes].filter(
    (n) => n.nodeType === 3 && n.textContent.trim().length > 0,
  );
  const spanNodes = el.querySelectorAll('span');
  const hasText =
    textNodes.length > 0 ||
    [...spanNodes].some((s) => !s.getAttribute('aria-hidden') && s.textContent.trim().length > 0);

  const iconEl = el.querySelector('[aria-hidden="true"][data-shape]');
  const hasIcon = iconEl !== null;

  return {
    pass: hasText && hasIcon,
    missingText: !hasText,
    missingIcon: !hasIcon,
  };
}
