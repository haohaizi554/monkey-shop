import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './runtime-tests',
  timeout: 120000,
  expect: {
    timeout: 15000,
  },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  outputDir: 'test-results/runtime',
  reporter: [['list']],
  use: {
    baseURL: process.env.RUNTIME_BASE_URL ?? 'http://127.0.0.1:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },
  projects: [
    {
      name: 'runtime-chromium',
      use: { ...devices['Desktop Chrome'], channel: 'chromium' },
    },
  ],
})
