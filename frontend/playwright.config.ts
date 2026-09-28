import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e', workers: 1, fullyParallel: false, timeout: 30000,
  use: { baseURL: process.env['FNORD_TEST_URL'] || 'http://127.0.0.1:8088',
    headless: true, trace: 'retain-on-failure',
    launchOptions: process.env['FNORD_CHROMIUM_EXECUTABLE'] ? {executablePath:process.env['FNORD_CHROMIUM_EXECUTABLE']} : {} },
  reporter: [['list'], ['html', { open:'never' }]]
});
