#!/usr/bin/env node
/**
 * 检查 FSD 依赖方向（pages → features → entities → shared）。
 * 用法：node scripts/check-deps.mjs
 *
 * 仅 grep import 路径前缀；规则简单，但足以堵住红线。
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SRC = join(__dirname, '..', 'src');

const banned = {
  'shared/': ['@app/', '@pages/', '@features/', '@entities/'],
  'entities/': ['@app/', '@pages/', '@features/'],
  'features/': ['@app/', '@pages/'],
  'pages/': ['@app/'],
};

let violations = 0;

async function* walk(dir) {
  for (const e of await readdir(dir)) {
    const p = join(dir, e);
    const s = await stat(p);
    if (s.isDirectory()) yield* walk(p);
    else if (/\.(ts|tsx)$/.test(p) && !p.endsWith('.test.tsx') && !p.endsWith('.test.ts')) yield p;
  }
}

for await (const file of walk(SRC)) {
  const rel = file.replace(SRC + '/', '');
  let layer = null;
  for (const k of Object.keys(banned)) {
    if (rel.startsWith(k)) {
      layer = k;
      break;
    }
  }
  if (!layer) continue;

  const text = await readFile(file, 'utf-8');
  // 类型-only 导入在运行时被擦除，不构成真正的依赖，允许跨层。
  const importRegex = /^\s*import\s+(type\s+)?[\s\S]+?from\s+['"]([^'"]+)['"]/gm;
  let m;
  while ((m = importRegex.exec(text)) !== null) {
    const isTypeOnly = Boolean(m[1]);
    if (isTypeOnly) continue;
    const spec = m[2];
    for (const bad of banned[layer]) {
      if (spec.startsWith(bad)) {
        console.error(
          `✗ ${rel}: layer "${layer}" must not import "${spec}" (use \`import type\` if it's only a type)`,
        );
        violations++;
      }
    }
  }
}

if (violations > 0) {
  console.error(`\nFSD dependency violations: ${violations}`);
  process.exit(1);
}
console.log('✓ FSD dependency direction OK');
