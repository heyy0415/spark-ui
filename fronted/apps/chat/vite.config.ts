import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const abs = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

const proxy = {
  // 只代理 API 前缀；SPA 路由本身留给前端
  '/agent/runs': { target: 'http://localhost:8080', changeOrigin: true },
  '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
};

/**
 * strato-chat（唯一应用）。@strato-ui/core 的解析策略（spec §2.2）：
 *   - serve / typecheck / vite-node：经 core package.json 的 exports 走 src（热更新）
 *   - build：alias 到 core 的 dist，保证生产构建吃的是可发布产物
 * alias 必须用正则精确匹配 —— 字符串键会前缀匹配，把 "@strato-ui/core/style.css" 改写成 "dist/index.js/style.css"。
 */
export default defineConfig(({ command }) => ({
  plugins: [react()],
  resolve: {
    alias: [
      { find: '@app', replacement: abs('./src/app') },
      { find: '@pages', replacement: abs('./src/pages') },
      { find: '@features', replacement: abs('./src/features') },
      { find: '@entities', replacement: abs('./src/entities') },
      { find: '@shared', replacement: abs('./src/shared') },
      // 只读契约别名：仅用于 import 示例 JSON（project-structure §1）
      { find: '@contracts', replacement: abs('../../../.harness/contracts') },
      ...(command === 'build'
        ? [{ find: /^@strato-ui\/core$/, replacement: abs('../../packages/core/dist/index.js') }]
        : []),
    ],
  },
  server: {
    port: 5173,
    strictPort: true,
    // dev server 需读取 workspace 内的 core src 与仓库内 .harness/contracts
    fs: { allow: [abs('../..'), abs('../../../.harness/contracts')] },
    proxy,
  },
  preview: {
    port: 4173,
    strictPort: true,
    proxy,
  },
}));
