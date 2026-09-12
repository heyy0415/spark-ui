#!/usr/bin/env node
/**
 * 三组检查：
 *   1. apps/chat 的 FSD 依赖方向（pages → features → entities → shared）
 *   2. packages/core/src/client 的 headless 边界：禁 react / react-dom、禁读构建期环境变量
 *   3. packages/core 的 client/ 与 react/ 禁 import 渲染层入口 '../index'（含测试文件、含 import type）
 *
 * 用法：node scripts/check-deps.mjs
 *
 * 仅 grep import 路径前缀；规则简单，但足以堵住红线。
 * （禁 antd 已由 .oxlintrc.json 的 `packages/core/src/**` override 覆盖，此处不重复。）
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const SRC = join(__dirname, '..', 'apps', 'chat', 'src');
const CORE_CLIENT = join(__dirname, '..', 'packages', 'core', 'src', 'client');
const CORE_REACT = join(__dirname, '..', 'packages', 'core', 'src', 'react');

const banned = {
  'shared/': ['@app/', '@pages/', '@features/', '@entities/'],
  'entities/': ['@app/', '@pages/', '@features/'],
  'features/': ['@app/', '@pages/'],
  'pages/': ['@app/'],
};

let violations = 0;

const isTest = (p) => p.endsWith('.test.ts') || p.endsWith('.test.tsx');

/** 递归产出 .ts / .tsx，含测试文件。 */
async function* walkAll(dir) {
  for (const e of await readdir(dir)) {
    const p = join(dir, e);
    const s = await stat(p);
    if (s.isDirectory()) yield* walkAll(p);
    else if (/\.(ts|tsx)$/.test(p)) yield p;
  }
}

/** 递归产出 .ts / .tsx，跳过测试文件（测试可为覆盖分支写额外 import）。 */
async function* walk(dir) {
  for await (const p of walkAll(dir)) {
    if (!isTest(p)) yield p;
  }
}

/**
 * 产出文件里所有**运行时**依赖的模块说明符。
 * `import type` 被 TS 擦除，不构成运行时依赖，故跳过。
 */
function* runtimeImports(text) {
  const re =
    /^\s*(?:import|export)\s+(?:(type\s+)?[^;'"]+?from\s+['"]([^'"]+)['"]|['"]([^'"]+)['"])|import\(\s*['"]([^'"]+)['"]\s*\)/gm;
  let m;
  while ((m = re.exec(text)) !== null) {
    if (m[1]) continue;
    const spec = m[2] ?? m[3] ?? m[4];
    if (spec !== undefined) yield spec;
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

  // 类型-only 导入在运行时被擦除，不构成真正的依赖，允许跨层（runtimeImports 已跳过）。
  for (const spec of runtimeImports(await readFile(file, 'utf-8'))) {
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

// ---- packages/core/src/client：headless 层的边界
// 它要能被任何 TS 工程 import（含非 React 宿主），所以不能碰 react；
// 也不能读构建期注入的环境变量——baseUrl 之类的缺省值属于应用层决策。
const CLIENT_BANNED_MODULES = ['react', 'react-dom'];
for await (const file of walkAll(CORE_CLIENT)) {
  const rel = 'packages/core/src/client/' + file.replace(CORE_CLIENT + '/', '');
  const text = await readFile(file, 'utf-8');

  for (const spec of runtimeImports(text)) {
    if (CLIENT_BANNED_MODULES.includes(spec)) {
      console.error(
        `✗ ${rel}: headless client must not import "${spec}" (React bindings belong in packages/core/src/react)`,
      );
      violations++;
    }
  }

  if (/import\s*\.\s*meta\s*\.\s*env/.test(text.replace(/\s+/g, ' '))) {
    console.error(
      `✗ ${rel}: headless client must not read build-time env (pass baseUrl in explicitly; defaults belong to the app layer)`,
    );
    violations++;
  }
}

// ---- client/ 与 react/ 都不得 import 渲染层入口 '../index'
// 真实踩过：useSparkRun.test.ts 从 '../index' 取类型，测试跑起来把 antd-mobile 的未编译
// CJS 拉进 vitest，报 SyntaxError。要类型就直指 '../schema/uiSchema' / '../registry/types'。
// 两点与上面的检查不同：
//   1. 含测试文件一起扫（walkAll）——坑就出在测试文件上；
//   2. `import type` 也算违规——vitest / vite 按模块图解析，type-only 同样会加载该入口。
const RENDER_ENTRY =
  /from\s+['"](\.\.|\.\.\/index(?:\.js)?)['"]|import\(\s*['"](\.\.|\.\.\/index(?:\.js)?)['"]\s*\)/g;
for (const dir of [CORE_CLIENT, CORE_REACT]) {
  const layer = dir === CORE_CLIENT ? 'client' : 'react';
  for await (const file of walkAll(dir)) {
    const rel = `packages/core/src/${layer}/` + file.replace(dir + '/', '');
    const text = await readFile(file, 'utf-8');
    let m;
    RENDER_ENTRY.lastIndex = 0;
    while ((m = RENDER_ENTRY.exec(text)) !== null) {
      const spec = m[1] ?? m[2];
      console.error(
        `✗ ${rel}: must not import the render-layer entry "${spec}" (it pulls in antd / antd-mobile, breaking headless consumers and vitest). Import the specific module, e.g. '../schema/uiSchema'.`,
      );
      violations++;
    }
  }
}

if (violations > 0) {
  console.error(`\ndependency violations: ${violations}`);
  process.exit(1);
}
console.log('✓ FSD dependency direction OK');
