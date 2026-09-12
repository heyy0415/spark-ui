import { defineConfig } from '@playwright/test';

/**
 * 前端 e2e（替代 .harness/scripts/e2e-frontend.mjs 的 puppeteer + 本机 Chrome 实现）。
 *
 * webServer 用 `pnpm run dev` 而非直接 `vite`：dev server 才注册 `/dev/schema`（`router.tsx` 的 `env.DEV` 门控，
 * 生产构建不注册），且走 pnpm 生命周期才会触发 `predev` 的 core dist 守护。
 * 端口默认 5199 而非 vite.config.ts 的 5173：后者 `strictPort: true`，本机开着 dev server 时会直接失败。
 * 后端地址经 `SPARK_BACKEND` 传给 dev server 的 proxy（vite.config.ts 的 proxy 对象 dev / preview 共用）。
 */
const PORT = Number(process.env['SPARK_FRONT_PORT'] ?? 5199);
const BASE_URL = `http://localhost:${PORT}`;

export default defineConfig({
  testDir: './e2e',
  // 端到端断言依赖真实后端状态（订单会被删除、退款会落库），并行会互相干扰
  workers: 1,
  fullyParallel: false,
  retries: 0,
  timeout: 60_000,
  reporter: [['list'], ['html', { open: 'never', outputFolder: 'e2e-report' }]],
  use: {
    baseURL: BASE_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    viewport: { width: 1280, height: 900 },
  },
  projects: [{ name: 'chromium', use: { browserName: 'chromium' } }],
  webServer: {
    // 注意不要写成 `run dev -- --port`：pnpm 会把 `--` 一并透传，vite 收到 `vite -- --port 5199` 时
    // 把 `--` 当位置参数，端口参数被忽略，dev server 仍起在 vite.config.ts 的 5173。
    command: `pnpm --filter spark-chat run dev --port ${PORT} --strictPort`,
    url: BASE_URL,
    // 不复用已有实例：本机手动起的 dev server 没有 SPARK_BACKEND，会静默代理到默认 8080，
    // 于是 e2e 连到一个没有 e2e profile（没有 fake 规划器）的后端，失败原因极难定位。
    reuseExistingServer: false,
    timeout: 120_000,
    stdout: 'ignore',
    stderr: 'pipe',
  },
});
