import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  build: {
    cssCodeSplit: false,
    rollupOptions: {
      output: {
        assetFileNames: 'assets/[name]-[hash][extname]',
        chunkFileNames: 'assets/[name]-[hash].js',
        entryFileNames: 'assets/[name]-[hash].js',

        /**
         * Manual chunk separation.
         *
         * Goals:
         *  - Technician (field) entry payload excludes dispatcher and charting bundles.
         *  - Charting library (Recharts / D3) lands in the 'operations' chunk only.
         *  - Each persona surface is independently cacheable.
         *
         * CI size-budget assertion: the field-surface entry must remain ≤ 100 kB
         * gzipped. See scripts/check-bundle-size.mjs (added by the CI pipeline).
         */
        manualChunks(id) {
          // Charting libraries land in the operations chunk only
          if (id.includes('recharts') || id.includes('d3-') || id.includes('/d3/')) {
            return 'chunk-operations';
          }

          // Surface chunks — each surface is its own cacheable chunk
          if (id.includes('/surfaces/dispatch/') || id.includes('/features/workorders/')) return 'chunk-dispatch';
          if (id.includes('/surfaces/field/')) return 'chunk-field';
          // Technician PWA shell — kept lean so it loads fast on mobile networks
          if (id.includes('/app/technician/')) return 'chunk-technician';
          if (id.includes('/surfaces/operations/')) return 'chunk-operations';
          if (id.includes('/surfaces/portal/')) return 'chunk-portal';
          if (id.includes('/surfaces/auth/'))  return 'chunk-auth';
          if (id.includes('/surfaces/admin/') || id.includes('/features/admin/')) return 'chunk-admin';

          // React Router shared across all surfaces
          if (id.includes('react-router')) return 'chunk-router';

          // TanStack Query shared across all surfaces
          if (id.includes('@tanstack/react-query')) return 'chunk-query';

          // Shell shared code (app/, components/, density/, appearance/)
          if (
            id.includes('/src/app/') ||
            id.includes('/src/components/') ||
            id.includes('/src/density/') ||
            id.includes('/src/appearance/')
          ) {
            return 'chunk-shell';
          }
        },
      },
    },
  },
  css: {
    devSourcemap: true,
  },
});
