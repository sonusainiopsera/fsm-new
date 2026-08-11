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
  ],
});
