/// <reference types="vitest" />
import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// https://vitejs.dev/config/
export default defineConfig({
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: './src/test/setup.ts',
  },
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      // Forward all BFF-bound requests to the Spring Boot BFF on :8080
      // The Vite dev server adds its own session cookie — the BFF session cookie is set-by the BFF.
      '/api':     { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/auth':    { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/oauth2':  { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/login':   { target: 'http://localhost:8080', changeOrigin: true, secure: false },
      '/logout':  { target: 'http://localhost:8080', changeOrigin: true, secure: false },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
  },
});
