import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',
        manualChunks(id) {
          // Charting library — isolated to the operations surface chunk so the
          // field (technician) entry never downloads chart code (AC-2).
          if (id.includes('recharts') || id.includes('victory') || id.includes('chart.js')) {
            return 'charting'
          }
          // React Router — shared vendor chunk
          if (id.includes('react-router') || id.includes('@remix-run')) {
            return 'vendor-router'
          }
          // TanStack Query — shared vendor chunk
          if (id.includes('@tanstack/react-query')) {
            return 'vendor-query'
          }
          // Surface-level code splitting
          if (id.includes('/surfaces/dispatch/')) return 'surface-dispatch'
          if (id.includes('/surfaces/operations/')) return 'surface-operations'
          if (id.includes('/surfaces/portal/')) return 'surface-portal'
          if (id.includes('/surfaces/field/')) return 'surface-field'
          // Vendor bundle
          if (id.includes('node_modules')) return 'vendor'
        },
      },
    },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.js'],
    globals: true,
    // Playwright tests live in tests/ — exclude them from Vitest
    include: ['src/**/*.{test,spec}.{js,jsx}', 'scripts/**/*.test.mjs'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'json', 'html'],
      thresholds: {
        lines: 80,
        branches: 80,
        functions: 80,
        statements: 80,
      },
      exclude: [
        'src/main.jsx',
        'src/test/**',
        'scripts/**',
        'eslint-local-rules/**',
        'docs/**',
        'tests/**',
        'playwright.config.js',
        'src/serviceWorker/fieldServiceWorker.js',
        'src/surfaces/**',
      ],
    },
  },
})
