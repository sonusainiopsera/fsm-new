import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration.
 *
 * Projects:
 *   chromium              — desktop a11y, keyboard, greyscale, reduced-motion
 *   chromium-dark         — dark-mode variant of the above
 *   mobile-technician     — 360×800 unit/component tests in tests/
 *   technician-mobile     — 360×800 E2E regression suite in e2e/ (WO-160)
 *
 * Run all:                npx playwright test
 * Run E2E suite only:     npx playwright test --project=technician-mobile
 * Run a11y gate:          npm run test:a11y
 */
export default defineConfig({
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  outputDir: 'playwright-results',
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'playwright-report/results.json' }],
    process.env.CI ? ['github'] : ['list'],
  ],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'on-first-retry',
  },
  projects: [
    {
      name: 'chromium',
      testDir: './tests',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium-dark',
      testDir: './tests',
      use: {
        ...devices['Desktop Chrome'],
        // dark appearance set via data attribute in test
      },
    },
    {
      name: 'mobile-technician',
      testDir: './tests',
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 360, height: 800 },
      },
    },
    // E2E regression suite — runs against the full frontend+backend stack.
    // Wall-clock budget: ≤ 8 min on a 4-core CI runner.
    // At most 1 automatic retry so genuine flakiness is visible (AC-8).
    {
      name: 'technician-mobile',
      testDir: './e2e',
      retries: 1,
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 360, height: 800 },
        deviceScaleFactor: 2,
        hasTouch: true,
        isMobile: true,
        userAgent:
          'Mozilla/5.0 (Linux; Android 11; Pixel 5) ' +
          'AppleWebKit/537.36 (KHTML, like Gecko) ' +
          'Chrome/90.0.4430.91 Mobile Safari/537.36',
        trace: 'on',
        video: 'on-first-failure',
        screenshot: 'on',
      },
    },
  ],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
