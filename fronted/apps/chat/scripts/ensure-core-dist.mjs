#!/usr/bin/env node
// predev / prebuild 守护：@strato-ui/core/style.css 始终指 dist，dist 不存在时 vite 会以 postcss ENOENT 白屏。
// 放在 chat 自己的 package.json 里而不是 workspace 根，避免 `--filter strato-chat dev` 绕过。
import { existsSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const styleCss = join(here, '..', '..', '..', 'packages', 'core', 'dist', 'style.css');
if (!existsSync(styleCss)) {
  console.error(
    '[strato-chat] @strato-ui/core dist not found. Run `pnpm -C fronted build:core` first.',
  );
  process.exit(1);
}
