import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    // Restrict to src/ so Playwright tests in tests/ are not picked up by Vitest
    include: ['src/**/*.{test,spec}.{js,jsx}'],
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test-setup.js'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      thresholds: {
        lines: 80,
        functions: 80,
        branches: 80,
        statements: 80,
      },
    },
  },
});
