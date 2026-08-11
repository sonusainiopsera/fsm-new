import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright configuration for composed-screen a11y, keyboard, greyscale,
 * reduced-motion and performance tests.
 *
 * Run: npx playwright test
 * Run a11y gate: npm run test:a11y
 * Run performance gate: npm run test:performance
 *
 * The dev server must be running (npm run dev) or use webServer config below.
 */
export default defineConfig({
  testDir: './tests',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['html', { outputFolder: 'playwright-report' }],
    ['json', { outputFile: 'playwright-report/results.json' }],
    process.env.CI ? ['github'] : ['list'],
  ],
  use: {
    baseURL: process.env.BASE_URL ?? 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'chromium-dark',
      use: {
        ...devices['Desktop Chrome'],
        // dark appearance set via data attribute in test
      },
    },
    {
      name: 'mobile-technician',
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 360, height: 800 },
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
