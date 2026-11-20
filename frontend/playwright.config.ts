import { defineConfig } from '@playwright/test';

const backendPort = process.env.E2E_BACKEND_PORT;
if (!backendPort || !/^\d{4,5}$/.test(backendPort) || Number(backendPort) > 65535 || backendPort === '8080') {
  throw new Error('Set E2E_BACKEND_PORT to the dedicated local test backend port (for example 18084).');
}

export default defineConfig({
  testDir: './e2e',
  outputDir: '../.cache/playwright/repair-intake',
  workers: 1,
  retries: 0,
  timeout: 120_000,
  use: { baseURL: 'http://127.0.0.1:5184', browserName: 'chromium', actionTimeout: 10_000 },
  webServer: {
    command: 'npm run dev -- --mode repair-e2e --host 127.0.0.1 --port 5184',
    url: 'http://127.0.0.1:5184',
    reuseExistingServer: false,
  },
});
