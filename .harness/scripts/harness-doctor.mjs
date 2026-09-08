#!/usr/bin/env node
/**
 * pnpm -C .harness run doctor
 *
 * 自检 .harness/ 体系的健康度，仅查事实、不修复：
 *   - 必需文件是否存在
 *   - SKILL.md frontmatter 是否合法
 *   - changes/ 下每个目录是否有 summary.md 且不停留在 TODO 太久
 *   - rules/ 引用的路径是否真存在
 *
 * 退出码 0 = 无致命问题（warning 不算致命）。
 */
import { readFile, readdir, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const H = join(root, '.harness');

let errors = 0;
let warnings = 0;

function err(msg) {
  console.error(`✗ ${msg}`);
  errors++;
}
function warn(msg) {
  console.warn(`! ${msg}`);
  warnings++;
}
function ok(msg) {
  console.log(`✓ ${msg}`);
}

const required = [
  'agents/platform-owner.md',
  'rules/project-structure.md',
  'rules/coding-standard.md',
  'rules/backend-standard.md',
  'rules/contracts.md',
  'rules/agent-safety.md',
  'rules/dev-workflow.md',
  'wiki/architecture.md',
  'wiki/domain-model.md',
  'wiki/api-contracts.md',
  'mcp/servers.json',
  'templates/change-template/summary.md',
  'scripts/new-change.mjs',
  'scripts/check-contracts.mjs',
  'scripts/check-module-deps.mjs',
  'scripts/check-seed.mjs',
  'scripts/gen-seed.mjs',
  'scripts/mvn.mjs',
  'scripts/ci.mjs',
  'scripts/sse-parse.mjs',
  'scripts/e2e-backend.sh',
  'scripts/e2e-frontend.mjs',
  'scripts/deploy-verify.sh',
  'scripts/preview-console.mjs',
  'scripts/lib/change-dir.mjs',
  'scripts/lib/change-dir.sh',
  'package.json',
];
// fronted 是 pnpm workspace：根 + packages/core + apps/chat（spec feat-strato-ui-monorepo §2.1）
const frontedRequired = [
  'pnpm-workspace.yaml',
  '.npmrc',
  'packages/core/package.json',
  'apps/chat/package.json',
];
for (const rel of ['fronted', 'backed']) {
  if (existsSync(join(root, rel))) ok(`dir: ${rel}/`);
  else err(`missing top-level dir: ${rel}/`);
}
if (existsSync(join(H, 'contracts'))) ok('dir: .harness/contracts/');
else err('missing .harness/contracts/');
for (const forbidden of ['package.json', 'pnpm-lock.yaml', 'node_modules', 'tsconfig.json', 'pom.xml']) {
  if (existsSync(join(root, forbidden))) err(`repo root must not contain ${forbidden} (fronted/ and backed/ are separate projects)`);
}
ok('repo root: no project files');
for (const rel of frontedRequired) {
  if (existsSync(join(root, 'fronted', rel))) ok(`fronted: ${rel}`);
  else err(`missing fronted workspace file: fronted/${rel}`);
}
if (existsSync(join(root, 'fronted', 'src'))) err('fronted/src must not exist (old single-app layout; code lives in fronted/apps/chat/src and fronted/packages/core/src)');
else ok('fronted: no legacy src/');
for (const rel of required) {
  if (existsSync(join(H, rel))) ok(`required: ${rel}`);
  else err(`missing required file: .harness/${rel}`);
}

// SKILL.md frontmatter
const skillsDir = join(H, 'skills');
const skillEntries = await readdir(skillsDir, { withFileTypes: true });
const skillNames = skillEntries.filter((e) => e.isDirectory()).map((e) => e.name);
for (const name of skillNames) {
  const skillFile = join(skillsDir, name, 'SKILL.md');
  if (!existsSync(skillFile)) {
    err(`skill missing SKILL.md: ${name}`);
    continue;
  }
  const text = await readFile(skillFile, 'utf-8');
  const m = text.match(/^---\n([\s\S]+?)\n---/);
  if (!m) {
    err(`skill ${name}: SKILL.md missing YAML frontmatter`);
    continue;
  }
  const front = m[1];
  if (!/name:\s*\S+/.test(front)) err(`skill ${name}: missing "name" in frontmatter`);
  if (!/description:\s*\S+/.test(front)) err(`skill ${name}: missing "description"`);
  if (front.length > 800) warn(`skill ${name}: description very long; consider trimming`);
  ok(`skill: ${name}`);
}


// pnpm 内置命令冲突守护：`pnpm ci` / `pnpm doctor` 是 pnpm 自带子命令，`pnpm -C .harness ci` 不会执行我们的脚本。
// 所有文档与脚本必须使用 `pnpm -C .harness run <script>`。
{
  const { readdir: rd } = await import('node:fs/promises');
  const scan = async (dir) => {
    const out = [];
    for (const e of await rd(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) {
        if (['node_modules', 'contracts', 'templates'].includes(e.name)) continue;
        if (dir.endsWith('changes') || p.includes('/changes/')) {
          if (e.name === 'review') continue;
        }
        out.push(...(await scan(p)));
      } else if (/\.(md|mjs|json)$/.test(e.name)) out.push(p);
    }
    return out;
  };
  const targets = [...(await scan(H)), join(root, 'CLAUDE.md'), join(root, 'AGENTS.md')];
  if (existsSync(join(root, 'docs'))) targets.push(...(await scan(join(root, 'docs'))));
  const bad = /pnpm -C \.harness (?!run\b)(doctor|ci|new-change|check-contracts|check-module-deps)\b/;
  let hits = 0;
  for (const t of targets) {
    if (!existsSync(t) || t.includes('/review/') || t.endsWith('harness-doctor.mjs')) continue;
    const text = await readFile(t, 'utf-8');
    const lines = text.split('\n');
    lines.forEach((l, i) => {
      if (bad.test(l)) {
        err(`pnpm shorthand collides with pnpm built-in: ${t.replace(root + '/', '')}:${i + 1} — use "pnpm -C .harness run <script>"`);
        hits++;
      }
    });
  }
  if (hits === 0) ok('pnpm command form: all use "pnpm -C .harness run <script>"');
}

// 文档路径存在检查（Hashimoto：文档引用的 fronted/ 路径必须真实存在，防止布局迁移后规则指向不存在的文件）
// 规则（spec feat-strato-ui-monorepo §2.5）：扫描 rules / skills / wiki / agents / CLAUDE.md / AGENTS.md（不含 changes/）；
// 只取反引号内以 `fronted/` 开头且不含空格的 token；含 * 或 { 时取第一个通配符之前的目录前缀；跳过 node_modules / dist。
{
  const { readdir: rd } = await import('node:fs/promises');
  const scanMd = async (dir) => {
    const out = [];
    for (const e of await rd(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) out.push(...(await scanMd(p)));
      else if (e.name.endsWith('.md')) out.push(p);
    }
    return out;
  };
  const docs = [join(root, 'CLAUDE.md'), join(root, 'AGENTS.md')];
  for (const d of ['rules', 'skills', 'wiki', 'agents']) {
    if (existsSync(join(H, d))) docs.push(...(await scanMd(join(H, d))));
  }
  let missing = 0;
  for (const doc of docs) {
    if (!existsSync(doc)) continue;
    const text = await readFile(doc, 'utf-8');
    for (const m of text.matchAll(/`(fronted\/[^`\s]+)`/g)) {
      const token = m[1];
      if (token.includes('node_modules') || token.includes('dist')) continue;
      const wild = token.search(/[*{]/);
      const probe = wild >= 0 ? token.slice(0, wild).replace(/[^/]*$/, '') : token;
      if (!existsSync(join(root, probe))) {
        err(`doc path does not exist: ${doc.replace(root + '/', '')} → \`${token}\` (checked ${probe})`);
        missing++;
      }
    }
  }
  if (missing === 0) ok('doc paths: every `fronted/...` reference exists');
}

// 脚本不得硬编码某个 change 的目录；统一经 scripts/lib/change-dir 定位
{
  const { readdir: rd } = await import('node:fs/promises');
  const scriptsDir = join(H, 'scripts');
  const needle = 'changes/' + 'feat-'; // 拼接以免本文件自身命中
  const walkScripts = async (dir) => {
    const out = [];
    for (const e of await rd(dir, { withFileTypes: true })) {
      const p = join(dir, e.name);
      if (e.isDirectory()) out.push(...(await walkScripts(p)));
      else if (/\.(sh|mjs)$/.test(e.name) && e.name !== 'harness-doctor.mjs') out.push(p);
    }
    return out;
  };
  let hits = 0;
  for (const f of await walkScripts(scriptsDir)) {
    const text = await readFile(f, 'utf-8');
    if (text.includes(needle)) {
      err(`script hard-codes a change dir: ${f.replace(H + '/', '')} — use scripts/lib/change-dir`);
      hits++;
    }
  }
  if (hits === 0) ok('scripts: no hard-coded change directory');
}

// L1 硬约束一致性（Hashimoto：上一 change 唯一 MUST FIX 是三份 L1 文件的 antd 措辞陈旧）
{
  const l1 = [join(root, 'CLAUDE.md'), join(root, 'AGENTS.md'), join(H, 'agents', 'platform-owner.md')];
  let bad = 0;
  for (const f of l1) {
    if (!existsSync(f)) continue;
    const text = await readFile(f, 'utf-8');
    const antdLine = text.split('\n').find((l) => /antd/.test(l) && /import|出现/.test(l));
    if (!antdLine || !antdLine.includes('packages/core/src/components/**')) {
      err(`L1 antd constraint stale in ${f.replace(root + '/', '')}: must reference packages/core/src/components/**`);
      bad++;
    }
    if (text.includes('shared/ui/**')) {
      err(`L1 file still mentions shared/ui/** (old layout): ${f.replace(root + '/', '')}`);
      bad++;
    }
  }
  if (bad === 0) ok('L1 antd constraint consistent across CLAUDE.md / AGENTS.md / platform-owner.md');
}

// changes
const changesDir = join(H, 'changes');
if (existsSync(changesDir)) {
  const entries = await readdir(changesDir, { withFileTypes: true });
  const items = entries.filter((e) => e.isDirectory()).map((e) => e.name);
  for (const c of items) {
    const sumPath = join(changesDir, c, 'summary.md');
    if (!existsSync(sumPath)) {
      err(`change ${c}: missing summary.md`);
      continue;
    }
    const text = await readFile(sumPath, 'utf-8');
    const todoCount = (text.match(/TODO/g) ?? []).length;
    const stats = await stat(sumPath);
    const ageDays = (Date.now() - stats.mtimeMs) / 86400000;
    if (todoCount > 0 && ageDays > 7) {
      warn(`change ${c}: has ${todoCount} TODO and inactive for ${ageDays.toFixed(0)}d`);
    }
    ok(`change: ${c} (${todoCount} TODO)`);
  }
}

console.log('');
if (errors > 0) {
  console.error(`harness-doctor: ${errors} errors, ${warnings} warnings`);
  process.exit(1);
}
console.log(`harness-doctor: 0 errors, ${warnings} warnings`);
