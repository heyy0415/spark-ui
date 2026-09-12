#!/usr/bin/env node
/**
 * pnpm -C spark-ui run verify-pack
 *
 * @spark-ui/core 发包就绪校验（spec §6.2 (a)–(g)；(h) 由 refactor-headless-client-into-core-20260912 增补）。一切子进程 cwd = packages/core，临时目录 packages/core/.verify-pack/
 * （zod / react / @types/react 只安装在 core 的 node_modules，vite-node 与 tsc 都从被加载文件所在目录向上解析）。
 *   (a) tarball 只含 package.json / README.md / dist/**
 *   (b) 解包后 package.json：exports 指 dist（publishConfig 已覆盖）、5 peer（渲染层 4 个标 optional）、无 dependencies、files
 *   (c) es-module-lexer 静态解析 dist/index.js 导出名 == RUNTIME_EXPORTS 清单
 *   (d) vite-node 加载解包后的 dist/index.js 成功
 *   (e) consumer.ts 引用全部运行时 + 类型导出，EOPT 开 / 关两次 tsc --noEmit 均 0
 *   (f) dist 体积 ≤ 基线 × 1.1（首次运行写入 verify-pack.baseline.json）
 *   (g) dist 内 .d.ts 不 import antd / antd-mobile / @ant-design
 *   (h) 三入口外部依赖闭包：'./client' 只需 zod、'./react' 只需 react + zod、'.' 才需 antd（headless 承诺的产物级证据）
 * 退出码 0 = 全部通过。结束（含失败）时删除 .verify-pack/。
 */
import { execFileSync, spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  readdirSync,
  readFileSync,
  rmSync,
  statSync,
  writeFileSync,
} from 'node:fs';
import { dirname, join, normalize, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { init, parse } from 'es-module-lexer';

const __dirname = dirname(fileURLToPath(import.meta.url));
const sparkUiDir = join(__dirname, '..');
const core = join(sparkUiDir, 'packages', 'core');
const work = join(core, '.verify-pack');
const baselinePath = join(__dirname, 'verify-pack.baseline.json');

/** spec §2.2 公共 API 固定清单 */
const RUNTIME_EXPORTS = [
  'SchemaRenderer',
  'ActionBar',
  'RunStatus',
  'SchemaSkeleton',
  'UnknownComponent',
  'desktopRegistry',
  'mobileRegistry',
  'REGISTRY_KEYS',
  'PROPS_SCHEMAS',
  'UiSchemaSchema',
  'UiComponentSchema',
  'UiActionSchema',
  'FormPropsSchema',
  'COMPONENT_TYPES',
  'parseUiSchema',
  'SparkThemeProvider',
  'SparkDeviceProvider',
  'useDevice',
  'MOBILE_MAX_WIDTH',
].toSorted();
const TYPE_EXPORTS = [
  'UiSchema',
  'UiComponent',
  'UiAction',
  'ComponentType',
  'FormProps',
  'CardProps',
  'TableProps',
  'TableRow',
  'ResultProps',
  'TimelineProps',
  'LabelValue',
  'InlineAction',
  'FormValues',
  'ComponentHandlers',
  'DeviceKind',
  'SchemaRendererProps',
  'ActionBarProps',
  'RunStatusProps',
  'RunStatusKind',
  'ToolStep',
  'SchemaSkeletonProps',
  'SparkThemeTokens',
  'SparkThemeProviderProps',
];
const PEERS = ['antd', 'antd-mobile', 'react', 'react-dom', 'zod'].toSorted();

let failures = 0;
const ok = (m) => console.log(`  ✓ ${m}`);
const fail = (m) => {
  console.error(`  ✗ ${m}`);
  failures++;
};
/** 条件为真记 ✓，否则记 ✗（避免把三元表达式当语句写）。 */
const check = (cond, okMsg, failMsg) => {
  if (cond) ok(okMsg);
  else fail(failMsg);
};
const sameSet = (a, b) => a.length === b.length && a.every((v, i) => v === b[i]);
/** 裸说明符归一到包名：'antd/locale/zh_CN' → 'antd'、'react/jsx-runtime' → 'react'。 */
const pkgOf = (spec) => (spec.startsWith('@') ? spec.split('/', 2).join('/') : spec.split('/')[0]);

function* walk(dir) {
  for (const e of readdirSync(dir)) {
    const p = join(dir, e);
    if (statSync(p).isDirectory()) yield* walk(p);
    else yield p;
  }
}
const dirSizeKb = (dir) =>
  Math.round([...walk(dir)].reduce((n, f) => n + statSync(f).size, 0) / 1024);

rmSync(work, { recursive: true, force: true });
mkdirSync(work, { recursive: true });
try {
  if (!existsSync(join(core, 'dist', 'index.js'))) {
    throw new Error('packages/core/dist missing — run `pnpm -C spark-ui build:core` first');
  }

  // ---- pack
  console.log('--- pack');
  execFileSync('pnpm', ['pack', '--pack-destination', work], { cwd: core, stdio: 'pipe' });
  const tgz = readdirSync(work).find((f) => f.endsWith('.tgz'));
  if (!tgz) throw new Error('pnpm pack produced no tarball');
  const entries = execFileSync('tar', ['-tzf', join(work, tgz)], { encoding: 'utf-8' })
    .trim()
    .split('\n')
    .map((l) => l.replace(/^package\//, ''));
  execFileSync('tar', ['-xzf', join(work, tgz), '-C', work]);
  const pkgDir = join(work, 'package');

  // (a)
  const stray = entries.filter(
    (e) => !(e === 'package.json' || e === 'README.md' || e.startsWith('dist/')),
  );
  check(
    stray.length === 0,
    `(a) tarball contains only package.json / README.md / dist (${entries.length} entries)`,
    `(a) unexpected tarball entries: ${stray.join(', ')}`,
  );

  // (b)
  const pkg = JSON.parse(readFileSync(join(pkgDir, 'package.json'), 'utf-8'));
  check(
    pkg.exports?.['.']?.import === './dist/index.js' &&
      pkg.exports?.['.']?.types === './dist/index.d.ts',
    '(b) exports["."] points to dist (publishConfig applied)',
    `(b) exports["."] = ${JSON.stringify(pkg.exports?.['.'])}`,
  );
  check(
    pkg.types === './dist/index.d.ts',
    '(b) types → ./dist/index.d.ts',
    `(b) types = ${pkg.types}`,
  );
  check(
    sameSet(Object.keys(pkg.peerDependencies ?? {}).toSorted(), PEERS),
    '(b) peerDependencies = 5 expected peers',
    `(b) peerDependencies = ${Object.keys(pkg.peerDependencies ?? {}).join(', ')}`,
  );
  // peer range 主版本必须与 workspace catalog 的主版本一致（spec §7：防两处漂移）
  const catalog = Object.fromEntries(
    [
      ...readFileSync(join(sparkUiDir, 'pnpm-workspace.yaml'), 'utf-8').matchAll(
        /^ {2}'?([@\w./-]+)'?:\s*['"]?[\^~>=]*\s*(\d+)/gm,
      ),
    ].map((m) => [m[1], m[2]]),
  );
  // 5 个 peer 必须都在 catalog 中且主版本一致；找不到也算失败（否则 catalog 解析失败会静默变绿）
  const peerMajorMismatch = Object.entries(pkg.peerDependencies ?? {}).filter(([name, range]) => {
    const major = /[\^~>=]*\s*(\d+)/.exec(String(range))?.[1];
    return catalog[name] === undefined || major !== catalog[name];
  });
  check(
    peerMajorMismatch.length === 0,
    '(b) peer ranges match catalog major versions',
    `(b) peer/catalog major mismatch: ${peerMajorMismatch.map(([n, r]) => `${n} ${r} vs catalog ${catalog[n]}`).join(', ')}`,
  );
  // 渲染层 peer 必须标 optional：只装 './client'（headless）的宿主不该因为缺 antd / react 被包管理器告警。
  // zod 三个入口都要，故必填。README 对外承诺了这点，这里守住它。
  const OPTIONAL_PEERS = ['antd', 'antd-mobile', 'react', 'react-dom'];
  const notOptional = OPTIONAL_PEERS.filter(
    (n) => pkg.peerDependenciesMeta?.[n]?.optional !== true,
  );
  check(
    notOptional.length === 0 && pkg.peerDependenciesMeta?.zod === undefined,
    '(b) render-layer peers are optional, zod stays required',
    `(b) peerDependenciesMeta wrong: not-optional=[${notOptional.join(', ')}] zodMarked=${pkg.peerDependenciesMeta?.zod !== undefined}`,
  );
  check(
    pkg.dependencies === undefined || Object.keys(pkg.dependencies).length === 0,
    '(b) no dependencies',
    `(b) unexpected dependencies: ${Object.keys(pkg.dependencies ?? {}).join(', ')}`,
  );
  check(
    sameSet([...(pkg.files ?? [])].toSorted(), ['README.md', 'dist']),
    '(b) files = [dist, README.md]',
    `(b) files = ${JSON.stringify(pkg.files)}`,
  );

  // (c)
  await init;
  const [, exps] = parse(readFileSync(join(pkgDir, 'dist', 'index.js'), 'utf-8'));
  const names = exps.map((e) => e.n).toSorted();
  check(
    sameSet(names, RUNTIME_EXPORTS),
    `(c) runtime exports (${names.length}) match spec list`,
    `(c) runtime exports differ. missing=${RUNTIME_EXPORTS.filter((n) => !names.includes(n))} extra=${names.filter((n) => !RUNTIME_EXPORTS.includes(n))}`,
  );

  // (g) — checked before (e) because (e) relies on it
  const dtsLeak = [...walk(join(pkgDir, 'dist'))]
    .filter((f) => f.endsWith('.d.ts'))
    // 既抓显式 `from 'antd…'`，也抓 tsc 为推断类型发射的内联 `import("antd/…")`
    .filter((f) => /(from ['"]|import\(['"])(antd|@ant-design)/.test(readFileSync(f, 'utf-8')));
  check(
    dtsLeak.length === 0,
    '(g) no antd / antd-mobile / @ant-design types leaked into dist .d.ts',
    `(g) antd types leaked: ${dtsLeak.map((f) => relative(pkgDir, f)).join(', ')}`,
  );
  const cssinjs = [...walk(join(pkgDir, 'dist'))].filter((f) =>
    readFileSync(f, 'utf-8').includes('ant-design/cssinjs'),
  );
  check(
    cssinjs.length === 0,
    '(g) antd runtime not bundled (no cssinjs signature)',
    '(g) antd source bundled into dist',
  );

  // (h) 三入口的外部依赖闭包 —— headless 承诺的机械证据。
  // 逐入口跟着相对 import 走完整个 chunk 图，收集所有裸模块说明符。
  // 只装 './client' 的宿主必须只需要 zod：这是 README 与 project-structure 对外的承诺，
  // 靠 lint 规则只能守源码，产物层面必须单独验（例如某个 chunk 被两个入口共享而意外带进 react）。
  //
  // 用 es-module-lexer 而非手写正则：rollup 把「只为副作用保留的外部依赖」编译成**无 from 的裸导入**
  // （`import "react";`），`/from\s*['"]…/` 会漏掉它 —— 本检查第一版就栽在这上面，自证没变红。
  const entryExternals = (entryRel) => {
    const distDir = join(pkgDir, 'dist');
    const seen = new Set();
    const external = new Set();
    const visit = (rel) => {
      if (seen.has(rel)) return;
      seen.add(rel);
      let code;
      try {
        code = readFileSync(join(distDir, rel), 'utf-8');
      } catch {
        return;
      }
      const [imports] = parse(code);
      for (const imp of imports) {
        const spec = imp.n;
        // imp.n 为 undefined 时说明是动态 import 且说明符非字面量；dist 里不该出现
        if (spec === undefined) continue;
        if (spec.startsWith('.')) {
          visit(normalize(join(dirname(rel), spec)));
        } else {
          external.add(spec);
        }
      }
    };
    visit(entryRel);
    return [...external].toSorted();
  };
  const ENTRY_EXPECTED = {
    'client/index.js': ['zod'],
    'react/index.js': ['react', 'zod'],
    'index.js': ['antd', 'antd-mobile', 'react', 'zod'],
  };
  for (const [entry, expected] of Object.entries(ENTRY_EXPECTED)) {
    const actual = [...new Set(entryExternals(entry).map(pkgOf))].toSorted();
    check(
      sameSet(actual, expected),
      `(h) ${entry} external deps = [${expected.join(', ')}]`,
      `(h) ${entry} external deps = [${actual.join(', ')}], expected [${expected.join(', ')}]`,
    );
  }

  // (d)
  const loader = join(work, 'load.mjs');
  writeFileSync(
    loader,
    `const m = await import(${JSON.stringify(join(pkgDir, 'dist', 'index.js'))});\nconsole.log('LOADED', Object.keys(m).length);\n`,
  );
  const vn = spawnSync(
    join(sparkUiDir, 'node_modules', '.bin', 'vite-node'),
    ['--root', core, loader],
    {
      cwd: core,
      encoding: 'utf-8',
    },
  );
  check(
    vn.status === 0 && vn.stdout.includes('LOADED'),
    `(d) vite-node loaded dist/index.js (${vn.stdout.trim().split(' ').pop()} exports)`,
    `(d) vite-node load failed: ${(vn.stderr || vn.stdout).split('\n').slice(0, 3).join(' | ')}`,
  );

  // (e)
  const consumer = [
    `import {`,
    ...RUNTIME_EXPORTS.map((n) => `  ${n},`),
    `} from '@spark-ui/core';`,
    `import type {`,
    ...TYPE_EXPORTS.map((n) => `  ${n},`),
    `} from '@spark-ui/core';`,
    `const runtime: unknown[] = [${RUNTIME_EXPORTS.join(', ')}];`,
    `type Types = [${TYPE_EXPORTS.join(', ')}];`,
    `export { runtime };`,
    `export type { Types };`,
  ].join('\n');
  writeFileSync(join(work, 'consumer.ts'), consumer);
  for (const eopt of [true, false]) {
    const tsconfig = {
      compilerOptions: {
        strict: true,
        exactOptionalPropertyTypes: eopt,
        module: 'ESNext',
        moduleResolution: 'bundler',
        target: 'ES2022',
        lib: ['ES2022', 'DOM'],
        jsx: 'react-jsx',
        types: [],
        skipLibCheck: false,
        noEmit: true,
        paths: { '@spark-ui/core': [join(pkgDir, 'dist', 'index.d.ts')] },
      },
      files: [join(work, 'consumer.ts')],
    };
    const tsPath = join(work, `tsconfig.eopt-${eopt}.json`);
    writeFileSync(tsPath, JSON.stringify(tsconfig, null, 2));
    const tsc = spawnSync(join(sparkUiDir, 'node_modules', '.bin', 'tsc'), ['-p', tsPath], {
      cwd: core,
      encoding: 'utf-8',
    });
    check(
      tsc.status === 0,
      `(e) consumer.ts typechecks against dist d.ts (exactOptionalPropertyTypes=${eopt})`,
      `(e) tsc EOPT=${eopt} failed: ${tsc.stdout.split('\n').slice(0, 4).join(' | ')}`,
    );
  }

  // (f)
  const sizeKb = dirSizeKb(join(pkgDir, 'dist'));
  // 基线只在显式 --write-baseline 时写入（pnpm 透传后 argv 含 "--"，用 includes 判断），缺失不再静默生成
  const writeBaseline = process.argv.includes('--write-baseline');
  if (writeBaseline) {
    writeFileSync(
      baselinePath,
      JSON.stringify({ distKb: sizeKb, recordedAt: new Date().toISOString() }, null, 2) + '\n',
    );
    ok(`(f) dist ${sizeKb} KB — baseline written to scripts/verify-pack.baseline.json`);
  } else if (!existsSync(baselinePath)) {
    fail('(f) baseline missing — run `pnpm -C spark-ui run verify-pack -- --write-baseline`');
  } else {
    const base = JSON.parse(readFileSync(baselinePath, 'utf-8')).distKb;
    check(
      sizeKb <= Math.ceil(base * 1.1),
      `(f) dist ${sizeKb} KB ≤ baseline ${base} KB × 1.1`,
      `(f) dist ${sizeKb} KB exceeds baseline ${base} KB × 1.1`,
    );
  }
} catch (e) {
  fail(e instanceof Error ? e.message : String(e));
} finally {
  rmSync(work, { recursive: true, force: true });
}

if (failures > 0) {
  console.error(`\nverify-pack: ${failures} failed`);
  process.exit(1);
}
console.log('\nverify-pack: all checks passed');
