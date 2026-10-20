import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// The built console goes into the engine's static resources, so the engine jar serves it (ADR-003).
// In development `npm run dev` serves it on 5173 and forwards API calls to a running engine on 8080.
export default defineConfig({
  plugins: [react()],
  build: {
    outDir: '../src/main/resources/static',
    emptyOutDir: true,
  },
  server: {
    proxy: {
      '/admin': 'http://localhost:8080',
      '/api': 'http://localhost:8080',
    },
  },
  test: {
    environment: 'jsdom',
  },
});
