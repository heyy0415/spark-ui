import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const alias = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': alias('./src'),
      '@app': alias('./src/app'),
      '@pages': alias('./src/pages'),
      '@features': alias('./src/features'),
      '@entities': alias('./src/entities'),
      '@shared': alias('./src/shared'),
      // 只读契约别名：仅用于 import 示例 JSON（project-structure §1）
      '@contracts': alias('../.harness/contracts'),
    },
  },
  server: {
    port: 5173,
    strictPort: true,
    // 允许 dev server 读取仓库内 .harness/contracts（默认只允许 fronted/ 工作区）
    fs: { allow: [alias('.'), alias('../.harness/contracts')] },
    // 后端单进程在 8080；SSE 需要关闭代理缓冲
    proxy: {
      // 只代理 API 前缀；SPA 路由 /agent 本身必须留给前端
      '/agent/runs': { target: 'http://localhost:8080', changeOrigin: true },
      '/actuator': { target: 'http://localhost:8080', changeOrigin: true },
    },
  },
  preview: {
    port: 4173,
    strictPort: true,
  },
});
