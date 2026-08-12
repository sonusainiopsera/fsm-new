/**
 * @fileoverview Offline simulation utilities for the technician E2E suite.
 *
 * Uses Playwright's context.setOffline() API combined with service-worker-aware
 * waiting so cached reads and refused writes are deterministic.
 */

/** Milliseconds to wait for the NotConnectedBanner to appear after going offline. */
const OFFLINE_BANNER_TIMEOUT_MS = 2_500

/** Milliseconds to wait for the NotConnectedBanner to clear after reconnecting. */
const RECONNECT_CLEAR_TIMEOUT_MS = 4_000

/**
 * Takes the browser context offline and waits for the application to detect
 * the disconnection (banner visible, navigator.onLine === false).
 *
 * @param {import('@playwright/test').BrowserContext} context
 * @param {import('@playwright/test').Page} page
 */
export async function goOffline(context, page) {
  await context.setOffline(true)
  // Wait for the connectivity hook to detect the disconnect
  await page
    .getByTestId('not-connected-banner')
    .waitFor({ state: 'visible', timeout: OFFLINE_BANNER_TIMEOUT_MS })
}

/**
 * Brings the browser context back online and waits for the application to
 * re-establish connectivity (banner hidden).
 *
 * @param {import('@playwright/test').BrowserContext} context
 * @param {import('@playwright/test').Page} page
 */
export async function goOnline(context, page) {
  await context.setOffline(false)
  await page
    .getByTestId('not-connected-banner')
    .waitFor({ state: 'hidden', timeout: RECONNECT_CLEAR_TIMEOUT_MS })
}

/**
 * Asserts that `navigator.onLine` is false in the page context.
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<boolean>}
 */
export async function isPageOffline(page) {
  return page.evaluate(() => !navigator.onLine)
}

/**
 * Asserts that no queued writes exist (service worker has no sync registrations
 * and the application has not stored any pending mutations).
 *
 * @param {import('@playwright/test').Page} page
 * @returns {Promise<boolean>} — true if no queued writes
 */
export async function hasNoQueuedWrites(page) {
  return page.evaluate(async () => {
    // Check service worker background-sync registrations
    if ('serviceWorker' in navigator && navigator.serviceWorker.controller) {
      try {
        const reg = await navigator.serviceWorker.getRegistration()
        if (reg && 'sync' in reg) {
          const tags = await reg.sync.getTags()
          if (tags.length > 0) return false
        }
      } catch {
        // sync API not available — no queued writes possible
      }
    }
    // Check application-level write queue (set by mutation components)
    const queue = window.__fieldServiceWriteQueue ?? []
    return queue.length === 0
  })
}
