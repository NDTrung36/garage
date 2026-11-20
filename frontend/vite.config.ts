import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

export default defineConfig(({ mode }) => ({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/api': {
        target: mode === 'repair-e2e'
          ? `http://127.0.0.1:${loadEnv(mode, '.', 'E2E_').E2E_BACKEND_PORT}`
          : 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
}));
