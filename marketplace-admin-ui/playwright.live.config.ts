import { defineConfig } from '@playwright/test';

const baseURL = process.env.MARKETPLACE_LIVE_BASE_URL ?? 'http://127.0.0.1:18080';

export default defineConfig({
  testDir: './e2e-live',
  fullyParallel: false,
  forbidOnly: true,
  retries: 0,
  workers: 1,
  reporter: 'list',
  timeout: 5 * 60 * 1000,
  use: {
    baseURL,
    browserName: 'chromium',
    headless: true,
    trace: 'retain-on-failure',
  },
});
