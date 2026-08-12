import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  build: {
    cssCodeSplit: false,
    // Bundle size budgets (AC-1): the technician shell entry chunk must stay
    // under 50 kB gzipped to load within 3 s on Fast 3G (~150 kB/s).
    chunkSizeWarningLimit: 500,
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
          // Technician PWA shell — isolated chunk for 360px mobile surface
          if (id.includes('/app/technician/')) return 'shell-technician'
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
