// @ts-check
const { defineConfig } = require('@playwright/test');

const baseURL = process.env.BASE_URL || 'http://localhost:8080';

module.exports = defineConfig({
  testDir: './tests',
  outputDir: './test-results',
  fullyParallel: true, // each test signs up its own user, so tests can't collide
  // Half the CPUs (Playwright's default), but at least 2 (CI's runners have
  // 2) and at most 4: Docker reports all of the host's CPUs, and more browsers
  // than its memory holds make every test slower and time some out.
  workers: Math.min(4, Math.max(2, Math.ceil(require('os').cpus().length / 2))),
  forbidOnly: true,
  retries: 0,
  timeout: 20_000,
  reporter: [['list']],
  use: {
    baseURL,
    // The app is at http://server:8080 in Docker; the browser gives a page
    // the device's location (Nearby) only when it's https or localhost, so
    // treat that address as if it were.
    launchOptions: { args: [`--unsafely-treat-insecure-origin-as-secure=${new URL(baseURL).origin}`] },
    screenshot: 'only-on-failure',
    trace: 'retain-on-failure',
  },
});
