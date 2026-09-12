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
      // 三个入口：渲染层（.）、headless（./client）、React 绑定（./react）。
      // 对象形式的 entry 让 rollup 按 key 命名产物，与 package.json 的 exports 一一对应。
      entry: {
        index: abs('./src/index.ts'),
        'client/index': abs('./src/client/index.ts'),
        'react/index': abs('./src/react/index.ts'),
      },
      formats: ['es'],
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
