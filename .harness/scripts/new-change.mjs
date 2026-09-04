#!/usr/bin/env node
/**
 * pnpm -C .harness run new-change <type> <name>
 * 例：pnpm -C .harness run new-change feat user-export
 *
 * 创建 .harness/changes/{type}-{name}-{YYYYMMDD}/ 目录骨架，
 * 把 templates/change-template/ 内的占位符替换后写入。
 */
import { mkdir, readFile, writeFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');

const [, , type, name] = process.argv;
if (!type || !name) {
  console.error('Usage: pnpm -C .harness run new-change <type> <name>');
  console.error('Example: pnpm -C .harness run new-change feat user-export');
  process.exit(1);
}
if (!/^[a-z]+$/.test(type)) {
  console.error(`type must be lowercase a-z (got "${type}")`);
  process.exit(1);
}
if (!/^[a-z0-9-]+$/.test(name)) {
  console.error(`name must be lowercase a-z 0-9 - (got "${name}")`);
  process.exit(1);
}

const today = new Date().toISOString().slice(0, 10).replaceAll('-', '');
const changeId = `${type}-${name}-${today}`;
const target = join(root, '.harness', 'changes', changeId);

if (existsSync(target)) {
  console.error(`Change directory already exists: ${target}`);
  process.exit(1);
}

const subdirs = ['request_analysis/review', 'coding/review', 'ci_result', 'deployment'];

await Promise.all(subdirs.map((d) => mkdir(join(target, d), { recursive: true })));

const templateDir = join(root, '.harness', 'templates', 'change-template');
const files = await readdir(templateDir);
for (const f of files) {
  const src = join(templateDir, f);
  const dst = join(target, f);
  let content = await readFile(src, 'utf-8');
  content = content
    .replaceAll('{{CHANGE_ID}}', changeId)
    .replaceAll('{{TYPE}}', type)
    .replaceAll('{{DATE}}', new Date().toISOString().slice(0, 10));
  await writeFile(dst, content, 'utf-8');
}

console.log(`✓ Created change directory: .harness/changes/${changeId}`);
console.log(`Next: invoke "request-analysis" Skill to fill spec.md and tasks.md`);
