#!/usr/bin/env node
/**
 * pnpm -C .harness run sync-contracts            —— 把契约真源同步到后端模块内副本
 * node .harness/scripts/sync-contracts.mjs --check  —— 只比对，不一致退出 1（check-contracts.mjs 末尾自动调用）
 *
 * 真源：.harness/contracts/*.schema.json 与 examples/*.example.json（invalid/ 反例不同步，只供 check-contracts 使用）。
 * 副本：spark-rooter/spark-rooter-contracts/src/main/resources/contracts/{*.schema.json, examples/*.example.json, examples/INDEX}
 *
 * 为什么要副本：契约 jar 以前用 pom 相对路径 ../../.harness/contracts 打包，模块无法脱离仓库目录独立构建与发布。
 * 副本是真源的机械派生物（字节级复制 + INDEX 按文件名排序），提交进 git；方向单向，改契约只改真源再跑本脚本。
 */
import { mkdir, readdir, readFile, writeFile, rm } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const src = join(root, '.harness', 'contracts');
const dst = join(root, 'spark-rooter', 'spark-rooter-contracts', 'src', 'main', 'resources', 'contracts');
const check = process.argv.includes('--check');

const schemas = (await readdir(src)).filter((f) => f.endsWith('.schema.json')).sort();
const examples = (await readdir(join(src, 'examples'))).filter((f) => f.endsWith('.example.json')).sort();
if (schemas.length === 0 || examples.length === 0) {
  console.error('✗ sync-contracts: source has no schemas or examples');
  process.exit(1);
}

// 期望的副本内容：相对路径 → 文本
const expected = new Map();
for (const f of schemas) expected.set(f, await readFile(join(src, f), 'utf-8'));
for (const f of examples) expected.set(join('examples', f), await readFile(join(src, 'examples', f), 'utf-8'));
// jar 内无法列目录：INDEX 供 ContractsSelfCheck 遍历示例（一行一个文件名）
expected.set(join('examples', 'INDEX'), examples.join('\n') + '\n');

async function listActual() {
  const out = new Map();
  if (!existsSync(dst)) return out;
  for (const f of await readdir(dst)) {
    if (f.endsWith('.schema.json')) out.set(f, await readFile(join(dst, f), 'utf-8'));
  }
  const ex = join(dst, 'examples');
  if (existsSync(ex)) {
    for (const f of await readdir(ex)) out.set(join('examples', f), await readFile(join(ex, f), 'utf-8'));
  }
  return out;
}

const actual = await listActual();
const diffs = [];
for (const [k, v] of expected) {
  if (!actual.has(k)) diffs.push(`missing: ${k}`);
  else if (actual.get(k) !== v) diffs.push(`differs: ${k}`);
}
for (const k of actual.keys()) {
  if (!expected.has(k)) diffs.push(`stale: ${k}`);
}

if (check) {
  if (diffs.length) {
    for (const d of diffs) console.error(`✗ sync-contracts: ${d}`);
    console.error(`sync-contracts: ${relative(root, dst)} out of date; run "pnpm -C .harness run sync-contracts"`);
    process.exit(1);
  }
  console.log(`✓ sync-contracts: ${expected.size} files in sync`);
  process.exit(0);
}

await rm(dst, { recursive: true, force: true });
await mkdir(join(dst, 'examples'), { recursive: true });
for (const [k, v] of expected) await writeFile(join(dst, k), v, 'utf-8');
console.log(`✓ sync-contracts: wrote ${expected.size} files to ${relative(root, dst)}${diffs.length ? ` (${diffs.length} changed)` : ' (no change)'}`);
