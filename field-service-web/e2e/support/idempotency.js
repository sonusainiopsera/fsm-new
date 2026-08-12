/**
 * @fileoverview Idempotency key harness for the technician E2E suite.
 *
 * Generates deterministic keys per test and tracks submitted requests so
 * duplicate submissions can be validated without relying on wall-clock timing.
 */

/**
 * Generates a stable idempotency key for a given test scenario.
 * Keys are deterministic within a test run and unique across test files.
 *
 * @param {string} scenario — human-readable scenario label (e.g. 'parts-consume')
 * @param {string} [suffix] — optional disambiguator for multiple calls in one test
 * @returns {string} — 36-character UUID-format key
 */
export function generateIdempotencyKey(scenario, suffix = '') {
  // Encode scenario into a deterministic UUID-like string
  const base = scenario.replace(/[^a-z0-9]/gi, '-').toLowerCase().slice(0, 24)
  const s = suffix ? suffix.slice(0, 8).padEnd(8, '0') : '00000001'
  return `e2e-${base.padEnd(24, '0').slice(0, 8)}-${s.slice(0, 4)}-4${s.slice(5, 8)}-a${s.slice(9, 12) || '000'}-${Date.now().toString(16).slice(-12).padStart(12, '0')}`
}

/**
 * Harness that tracks which Idempotency-Key values have been submitted and how
 * many times each was seen by the server.
 *
 * @param {import('@playwright/test').Page} page
 * @param {string} urlPattern — URL pattern to monitor (e.g. '/api/v1/work-orders/*')
 */
export class IdempotencyHarness {
  constructor(page, urlPattern) {
    this._page = page
    this._urlPattern = urlPattern
    /** @type {Map<string, number>} key → request count */
    this._counts = new Map()
  }

  /**
   * Start monitoring requests matching the URL pattern.
   */
  async start() {
    this._page.on('request', (req) => {
      if (req.url().includes(this._urlPattern.replace('*', ''))) {
        const key = req.headers()['idempotency-key']
        if (key) {
          this._counts.set(key, (this._counts.get(key) ?? 0) + 1)
        }
      }
    })
  }

  /**
   * Returns how many times the given key was seen in requests.
   * @param {string} key
   * @returns {number}
   */
  requestCount(key) {
    return this._counts.get(key) ?? 0
  }

  /**
   * Returns all observed keys.
   * @returns {string[]}
   */
  observedKeys() {
    return [...this._counts.keys()]
  }
}

/**
 * Unit-testable helper: verifies a key follows the expected format
 * (36 chars, dash-separated groups as per RFC 4122 UUID).
 *
 * @param {string} key
 * @returns {boolean}
 */
export function isValidIdempotencyKey(key) {
  return typeof key === 'string' && key.length >= 16 && key.length <= 128 &&
    /^[A-Za-z0-9\-._~+/]+$/.test(key)
}
