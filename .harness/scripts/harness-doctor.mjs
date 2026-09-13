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
  'scripts/sync-contracts.mjs',
  'scripts/check-module-deps.mjs',
  'scripts/check-seed.mjs',
  'scripts/gen-seed.mjs',
  'scripts/mvn.mjs',
  'scripts/check-rename.mjs',
  'scripts/check-log-assertions.mjs',
  'scripts/ci.mjs',
  'scripts/sse-parse.mjs',
  'scripts/e2e-backend.sh',
  'scripts/e2e-frontend.sh',
  'scripts/deploy-verify.sh',
  'scripts/preview-console.mjs',
  'scripts/lib/change-dir.mjs',
  'scripts/lib/change-dir.sh',
  'scripts/lib/java-home.sh',
  'package.json',
];
// sparkUiDir 是 pnpm workspace：根 + packages/core + apps/chat（spec feat-spark-ui-monorepo §2.1）
const sparkUiRequired = [
  'pnpm-workspace.yaml',
  '.npmrc',
  'packages/core/package.json',
  'apps/chat/package.json',
];
for (const rel of ['spark-ui', 'spark-rooter']) {
  if (existsSync(join(root, rel))) ok(`dir: ${rel}/`);
  else err(`missing top-level dir: ${rel}/`);
}
if (existsSync(join(H, 'contracts'))) ok('dir: .harness/contracts/');
else err('missing .harness/contracts/');
for (const forbidden of ['package.json', 'pnpm-lock.yaml', 'node_modules', 'tsconfig.json', 'pom.xml']) {
  if (existsSync(join(root, forbidden))) err(`repo root must not contain ${forbidden} (sparkUiDir/ and sparkRooterDir/ are separate projects)`);
}
ok('repo root: no project files');

// 根 README 用词门禁（change feat-chat-conversation-ui）：口号式 / AI 味词不进工程说明
{
  const readme = join(root, 'README.md');
  if (existsSync(readme)) {
    const text = await readFile(readme, 'utf-8');
    const banned = ['一句话', '赋能', '全面', '极致', '赋予', '开箱即用', '无缝', '革命性', '重新定义'];
    const hit = banned.filter((w) => text.includes(w));
    if (hit.length) err(`README.md contains slogan-style words: ${hit.join(' / ')}`);
    else ok('README.md free of slogan-style words');
  }
}
for (const rel of sparkUiRequired) {
  if (existsSync(join(root, 'spark-ui', rel))) ok(`sparkUiDir: ${rel}`);
  else err(`missing sparkUiDir workspace file: sparkUiDir/${rel}`);
}
if (existsSync(join(root, 'spark-ui', 'src'))) err('sparkUiDir/src must not exist (old single-app layout; code lives in sparkUiDir/apps/chat/src and sparkUiDir/packages/core/src)');
else ok('sparkUiDir: no legacy src/');
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

// 文档路径存在检查（Hashimoto：文档引用的 sparkUiDir/ 路径必须真实存在，防止布局迁移后规则指向不存在的文件）
// 规则（spec feat-spark-ui-monorepo §2.5）：扫描 rules / skills / wiki / agents / CLAUDE.md / AGENTS.md（不含 changes/）；
// 只取反引号内以 `spark-ui/` 开头且不含空格的 token；含 * 或 { 时取第一个通配符之前的目录前缀；跳过 node_modules / dist。
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
    for (const m of text.matchAll(/`(sparkUiDir\/[^`\s]+)`/g)) {
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
  if (missing === 0) ok('doc paths: every `sparkUiDir/...` reference exists');
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

// Playwright 版本一致性（change test-e2e-playwright-fake-llm）：.harness 的 `playwright` 与 spark-ui 的
// `@playwright/test` 必须精确同版本 —— 同版本共用一份 ~/Library/Caches/ms-playwright/chromium-*，
// 版本漂移会静默触发第二份 ~150MB 下载，且两套 e2e 可能跑在不同浏览器上。
{
  const readJson = async (p) => (existsSync(p) ? JSON.parse(await readFile(p, 'utf-8')) : null);
  const harnessPkg = await readJson(join(H, 'package.json'));
  const uiPkg = await readJson(join(root, 'spark-ui', 'package.json'));
  const harnessVer = harnessPkg?.devDependencies?.playwright;
  const uiVer = uiPkg?.devDependencies?.['@playwright/test'];
  if (!harnessVer) {
    err('.harness/package.json: missing devDependency "playwright" (preview-console.mjs needs it)');
  } else if (!uiVer) {
    err('spark-ui/package.json: missing devDependency "@playwright/test"');
  } else if (harnessVer !== uiVer) {
    err(`playwright version drift: .harness "playwright"=${harnessVer} vs spark-ui "@playwright/test"=${uiVer} — must be identical or a second chromium gets downloaded`);
  } else {
    ok(`playwright version pinned consistently (${harnessVer})`);
  }
}

// 冻结产物 / 报告 / 评审卫生（评审 N-5）：changes/** 任何文件不得含密钥形态字面量或内部 LLM 网关域名。
// 模式用拼接构造，避免本文件自命中；只认形态，不依赖 LIVE 环境变量（rule 模式 / doctor 也生效）。
{
  const secretShapes = [
    new RegExp('sk-' + '[A-Za-z0-9_-]{16,}'),
    new RegExp('sss' + 'aiapi\\.com'),
    new RegExp('gpt-' + '\\d(\\.\\d)?-[a-z]+'),
  ];
  const changesRoot = join(H, 'changes');
  let hits = 0;
  if (existsSync(changesRoot)) {
    const walk = async (dir) => {
      for (const e of await readdir(dir, { withFileTypes: true })) {
        const p = join(dir, e.name);
        if (e.isDirectory()) await walk(p);
        else if (/\.(md|log|json|out|txt)$/.test(e.name)) {
          const text = await readFile(p, 'utf-8').catch(() => '');
          for (const re of secretShapes) if (re.test(text)) { err(`secret-shaped literal in ${p.replace(root + '/', '')} (${re.source.slice(0, 12)}…)`); hits++; break; }
        }
      }
    };
    await walk(changesRoot);
  }
  if (hits === 0) ok('changes/** free of secret-shaped literals (LLM key / gateway host / model name)');
}

// 构建配置不得指向公司内网（fix/mvnw-public-distribution）：
// maven-wrapper.properties 的 distributionUrl 曾是内网 Nexus——本机 ~/.m2/settings.xml 有镜像，从没暴露；
// GitHub Actions 第一次真跑就 wget 失败。别人 clone 后也一样跑不起来。
// 只查可执行 / 可解析的配置文件；changes/** 的历史报告里提到内网地址是事实记录，不算。
{
  const INTERNAL_HOST = new RegExp('zhuan' + 'spirit\\.com|zhuan' + 'inc\\.com|bj58\\.com|nexus\\.[a-z0-9-]+\\.(com|cn|net)(?!/repository/maven-releases/$)');
  const configFiles = [
    'spark-rooter/.mvn/wrapper/maven-wrapper.properties',
    'spark-rooter/pom.xml',
    'spark-rooter/examples/host-demo/pom.xml',
    'spark-rooter/examples/provider-demo/pom.xml',
    'spark-ui/.npmrc',
    'spark-ui/pnpm-workspace.yaml',
    '.harness/scripts/mvn.mjs',
    '.harness/scripts/ci.mjs',
    '.github/workflows/ci.yml',
    'Dockerfile',
  ];
  let internalHits = 0;
  for (const rel of configFiles) {
    const p = join(root, rel);
    if (!existsSync(p)) continue;
    const text = await readFile(p, 'utf-8');
    // README 里的 nexus.example.com 是示例占位，不在本清单；这里只扫真配置
    if (INTERNAL_HOST.test(text)) { err(`internal host in build config: ${rel} (the repo is public; clone-and-build must not depend on a private network)`); internalHits++; }
  }
  if (internalHits === 0) ok('build config free of internal hosts (wrapper / pom / npmrc / CI)');
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
