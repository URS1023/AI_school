import { defineConfig } from '@playwright/test'

/**
 * 端到端测试：把 PRD 第 17 章的验收用例跑在真实浏览器 + 真实后端上。
 * 前提：后端以 dev 配置运行在 JB_API_BASE（默认 http://127.0.0.1:8080，自带演示数据）。
 * 浏览器默认用本机 Chrome（JB_E2E_CHANNEL 可改为 msedge），不需要额外下载。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  fullyParallel: false,
  retries: 0,
  reporter: [['list']],
  use: {
    channel: process.env.JB_E2E_CHANNEL ?? 'chrome',
    headless: true,
    locale: 'zh-CN',
    trace: 'retain-on-failure',
    testIdAttribute: 'data-test',
  },
  projects: [
    { name: 'teacher-web', use: { baseURL: 'http://localhost:5173' }, testMatch: /teacher\..*\.spec\.ts/ },
    { name: 'admin-web', use: { baseURL: 'http://localhost:5174' }, testMatch: /admin\..*\.spec\.ts/ },
  ],
  webServer: [
    { command: 'pnpm dev:teacher', url: 'http://localhost:5173', reuseExistingServer: true, timeout: 120_000 },
    { command: 'pnpm dev:admin', url: 'http://localhost:5174', reuseExistingServer: true, timeout: 120_000 },
  ],
})
