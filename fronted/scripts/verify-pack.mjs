#!/usr/bin/env node
/**
 * pnpm -C fronted run verify-pack
 *
 * @strato-ui/core 发包就绪校验（spec §6.2 (a)–(g)）。一切子进程 cwd = packages/core，临时目录 packages/core/.verify-pack/
 * （zod / react / @types/react 只安装在 core 的 node_modules，vite-node 与 tsc 都从被加载文件所在目录向上解析）。
 *   (a) tarball 只含 package.json / README.md / dist/**
 *   (b) 解包后 package.json：exports 指 dist（publishConfig 已覆盖）、6 peer、无 dependencies、files
 *   (c) es-module-lexer 静态解析 dist/index.js 导出名 == 17 项运行时清单
 *   (d) vite-node 加载解包后的 dist/index.js 成功
 *   (e) consumer.ts 引用 17 运行时 + 10 类型导出，EOPT 开 / 关两次 tsc --noEmit 均 0
 *   (f) dist 体积 ≤ 基线 × 1.1（首次运行写入 verify-pack.baseline.json）
 *   (g) dist 内 .d.ts 不 import antd / antd-mobile / @ant-design
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
import { dirname, join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';
import { init, parse } from 'es-module-lexer';

const __dirname = dirname(fileURLToPath(import.meta.url));
const fronted = join(__dirname, '..');
const core = join(fronted, 'packages', 'core');
const work = join(core, '.verify-pack');
const baselinePath = join(__dirname, 'verify-pack.baseline.json');

/** spec §2.2 公共 API 固定清单 */
const RUNTIME_EXPORTS = [
  'SchemaRenderer',
  'ActionBar',
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
  'StratoThemeProvider',
  'StratoDeviceProvider',
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
  'StratoThemeTokens',
  'StratoThemeProviderProps',
];
const PEERS = ['@ant-design/icons', 'antd', 'antd-mobile', 'react', 'react-dom', 'zod'].toSorted();

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
    throw new Error('packages/core/dist missing — run `pnpm -C fronted build:core` first');
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
    '(b) peerDependencies = 6 expected peers',
    `(b) peerDependencies = ${Object.keys(pkg.peerDependencies ?? {}).join(', ')}`,
  );
  // peer range 主版本必须与 workspace catalog 的主版本一致（spec §7：防两处漂移）
  const catalog = Object.fromEntries(
    [
      ...readFileSync(join(fronted, 'pnpm-workspace.yaml'), 'utf-8').matchAll(
        /^ {2}'?([@\w./-]+)'?:\s*['"]?[\^~>=]*\s*(\d+)/gm,
      ),
    ].map((m) => [m[1], m[2]]),
  );
  // 6 个 peer 必须都在 catalog 中且主版本一致；找不到也算失败（否则 catalog 解析失败会静默变绿）
  const peerMajorMismatch = Object.entries(pkg.peerDependencies ?? {}).filter(([name, range]) => {
    const major = /[\^~>=]*\s*(\d+)/.exec(String(range))?.[1];
    return catalog[name] === undefined || major !== catalog[name];
  });
  check(
    peerMajorMismatch.length === 0,
    '(b) peer ranges match catalog major versions',
    `(b) peer/catalog major mismatch: ${peerMajorMismatch.map(([n, r]) => `${n} ${r} vs catalog ${catalog[n]}`).join(', ')}`,
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

  // (d)
  const loader = join(work, 'load.mjs');
  writeFileSync(
    loader,
    `const m = await import(${JSON.stringify(join(pkgDir, 'dist', 'index.js'))});\nconsole.log('LOADED', Object.keys(m).length);\n`,
  );
  const vn = spawnSync(
    join(fronted, 'node_modules', '.bin', 'vite-node'),
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
    `} from '@strato-ui/core';`,
    `import type {`,
    ...TYPE_EXPORTS.map((n) => `  ${n},`),
    `} from '@strato-ui/core';`,
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
        paths: { '@strato-ui/core': [join(pkgDir, 'dist', 'index.d.ts')] },
      },
      files: [join(work, 'consumer.ts')],
    };
    const tsPath = join(work, `tsconfig.eopt-${eopt}.json`);
    writeFileSync(tsPath, JSON.stringify(tsconfig, null, 2));
    const tsc = spawnSync(join(fronted, 'node_modules', '.bin', 'tsc'), ['-p', tsPath], {
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
    fail('(f) baseline missing — run `pnpm -C fronted run verify-pack -- --write-baseline`');
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
