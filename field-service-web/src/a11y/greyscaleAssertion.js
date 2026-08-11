/**
 * @fileoverview Greyscale survivability assertion helpers.
 *
 * Each status indicator (Chip, KPI delta, risk indicator) must convey meaning
 * through colour AND text label AND a distinct icon or shape (BR-34).
 * When colour is removed (CSS greyscale filter), text + icon must still be present.
 */

/**
 * Checks that a status element carries both a text node and an icon element.
 * Uses a DOM element reference (works in jsdom and real browsers via Playwright).
 *
 * @param {Element} el  The status indicator element (e.g. a rendered Chip)
 * @returns {{ hasText: boolean, hasIcon: boolean, passes: boolean, details: string }}
 */
export function assertGreyscaleSurvivability(el) {
  const text = el.textContent?.trim() ?? ''
  const hasText = text.length > 0

  // An icon element is: an element with aria-hidden="true", or a role="img", or
  // a known icon class, or a Unicode symbol span (non-alphanumeric characters).
  const iconEl = el.querySelector('[aria-hidden="true"]')
    ?? el.querySelector('[role="img"]')
    ?? el.querySelector('svg')

  // Fallback: check for non-alphanumeric Unicode text (symbolic characters used as icons)
  const spans = Array.from(el.querySelectorAll('span'))
  const hasSymbol = spans.some(span => {
    const t = span.textContent?.trim() ?? ''
    return t.length > 0 && t.length <= 3 && /[^\w\s]/.test(t)
  })

  const hasIcon = iconEl !== null || hasSymbol

  const passes = hasText && hasIcon
  const details = [
    hasText ? null : 'missing text label',
    hasIcon ? null : 'missing icon/shape element',
  ].filter(Boolean).join(', ')

  return { hasText, hasIcon, passes, details: details || 'OK' }
}

/**
 * Walks all elements matching a selector within a container and asserts
 * that each one passes greyscale survivability.
 *
 * @param {Element} container  root element to search within
 * @param {string} selector  CSS selector for status indicator elements
 * @returns {{ total: number, failures: Array<{ element: Element, details: string }> }}
 */
export function auditGreyscale(container, selector = '[data-kind]') {
  const elements = Array.from(container.querySelectorAll(selector))
  const failures = []

  for (const el of elements) {
    const result = assertGreyscaleSurvivability(el)
    if (!result.passes) {
      failures.push({ element: el, details: result.details })
    }
  }

  return { total: elements.length, failures }
}
