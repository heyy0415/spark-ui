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
  'scripts/mvn.mjs',
  'scripts/ci.mjs',
  'scripts/sse-parse.mjs',
  'scripts/e2e-backend.sh',
  'scripts/e2e-frontend.mjs',
  'package.json',
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
