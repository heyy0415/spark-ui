import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
// vitest/config 的 defineConfig 是 vite 的超集，带 test 字段类型；serve / build 行为不变
import { defineConfig } from 'vitest/config';

const abs = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

// 后端地址可用 SPARK_BACKEND 覆盖（默认 8080；验收脚本在 8080 被占用时用 8091 等自起实例）
const backend = process.env['SPARK_BACKEND'] ?? 'http://localhost:8080';
const proxy = {
  // 只代理 API 前缀；SPA 路由本身留给前端
  '/agent/runs': { target: backend, changeOrigin: true },
  '/actuator': { target: backend, changeOrigin: true },
};

/**
 * spark-chat（唯一应用）。@spark-ui/core 的解析策略（spec §2.2）：
 *   - serve / typecheck / vite-node：经 core package.json 的 exports 走 src（热更新）
 *   - build：alias 到 core 的 dist，保证生产构建吃的是可发布产物
 * alias 必须用正则精确匹配 —— 字符串键会前缀匹配，把 "@spark-ui/core/style.css" 改写成 "dist/index.js/style.css"。
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
        ? [{ find: /^@spark-ui\/core$/, replacement: abs('../../packages/core/dist/index.js') }]
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
  // 单元测试只测纯函数与传输层（environment: node，不引入 jsdom）。测试经 @spark-ui/core 入口连带引入 antd-mobile，
  // 其 CJS 入口 require("./global.css") 在 Node 里无法加载；用 vitest 的 ssr 依赖预打包把它交给 esbuild，并把 .css 置为空模块。
  // 试过 server.deps.inline / ssr.noExternal 都不生效（vitest 4.1），只有这一组合可行。
  test: {
    environment: 'node',
    css: false,
    deps: {
      optimizer: {
        ssr: {
          enabled: true,
          include: ['antd-mobile'],
          esbuildOptions: { loader: { '.css': 'empty' } },
        },
      },
    },
  },
}));
