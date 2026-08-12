import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  timeout: 30_000,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 2 : undefined,
  reporter: [
    ['list'],
    ['json', { outputFile: 'test-results/playwright-report.json' }],
    ['html', { outputFolder: 'test-results/playwright-html', open: 'never' }],
  ],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
  projects: [
    {
      name: 'a11y',
      testMatch: 'tests/a11y/**/*.spec.js',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'performance',
      testMatch: 'tests/performance/**/*.spec.js',
      use: { ...devices['Desktop Chrome'] },
    },
    {
      name: 'technician',
      testMatch: 'tests/technician/**/*.spec.js',
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 360, height: 800 },
      },
    },
    // WO-160: Full regression suite — mobile viewport with trace + video on failure
    {
      name: 'technician-mobile',
      testMatch: 'e2e/technician/**/*.spec.js',
      retries: 1,
      use: {
        ...devices['Pixel 5'],
        viewport: { width: 360, height: 800 },
        deviceScaleFactor: 2,
        isMobile: true,
        hasTouch: true,
        video: 'on',
        trace: 'on-first-retry',
        screenshot: 'only-on-failure',
      },
    },
  ],
});
