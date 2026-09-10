import { fileURLToPath, URL } from 'node:url';
import react from '@vitejs/plugin-react';
import { defineConfig } from 'vite';

const abs = (p: string): string => fileURLToPath(new URL(p, import.meta.url));

/**
 * @spark-ui/core 库构建（spec §2.2）：ESM 单入口，全部 peer 及其深路径 external，
 * CSS Modules 合并为 dist/style.css，React.lazy 组件按 chunks/ 分文件。声明文件由 tsc -p tsconfig.build.json 产出。
 */
export default defineConfig({
  plugins: [react()],
  build: {
    lib: {
      entry: abs('./src/index.ts'),
      formats: ['es'],
      fileName: 'index',
      cssFileName: 'style',
    },
    sourcemap: false,
    rollupOptions: {
      external: [
        /^react(\/.*)?$/,
        /^react-dom(\/.*)?$/,
        /^antd(\/.*)?$/,
        /^antd-mobile(\/.*)?$/,
        /^@ant-design\/.*/,
        /^zod(\/.*)?$/,
      ],
      output: {
        chunkFileNames: 'chunks/[name]-[hash].js',
      },
    },
  },
});
