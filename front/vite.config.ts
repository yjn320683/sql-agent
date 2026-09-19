import { loadEnv } from 'vite';
import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, '.', '');
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8382';

  return {
    plugins: [react()],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            'vendor-react': ['react', 'react-dom', 'react-router-dom'],
          },
        },
      },
    },
    test: {
      exclude: ['e2e/**', 'node_modules/**', 'dist/**'],
    },
    server: {
      port: 8282,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
        '/v1': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
      },
    },
  };
});
