import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: 0,
  workers: 1,
  reporter: 'list',
  use: {
    baseURL: 'http://127.0.0.1:4173',
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
  webServer: {
    command: 'npm run build && node e2e/static-server.mjs',
    url: 'http://127.0.0.1:4173/dev-admin/',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
