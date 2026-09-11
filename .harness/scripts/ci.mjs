#!/usr/bin/env node
/**
 * pnpm -C .harness run ci
 *
 * 全仓单一质量门禁（在仓库根或 .harness/ 下执行均可）。按顺序：
  *   0. check-rename         —— 旧项目名 / 旧顶层目录名残留（见脚本头注释）
 *   1. check-contracts      —— .harness/contracts/ Schema 与示例
 *   2. check-module-deps    —— 后端模块依赖红线
 *   3. spark-ui              —— pnpm -C spark-ui run ci（typecheck + lint + format:check + build）
 *   4. spark-rooter          —— ./mvnw -q -B install（编译 + JUnit 单测 + spotless + 进本地仓；pom.xml 不存在时跳过）
 *   5. host-demo             —— examples/host-demo mvn -q -o package（离线，只依赖本地仓）
 *
 * 任一步骤非 0 立即停止并以该退出码退出。最后打印每步退出码摘要。
 */
import { spawnSync } from 'node:child_process';
import { existsSync, rmSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const harness = join(__dirname, '..');
const root = join(harness, '..');

const steps = [
  { name: 'check-rename', cmd: 'node', args: [join(harness, 'scripts', 'check-rename.mjs')] },
  { name: 'check-contracts', cmd: 'node', args: [join(harness, 'scripts', 'check-contracts.mjs')] },
  { name: 'check-module-deps', cmd: 'node', args: [join(harness, 'scripts', 'check-module-deps.mjs')] },
  { name: 'check-seed', cmd: 'node', args: [join(harness, 'scripts', 'check-seed.mjs')] },
  { name: 'spark-ui', cmd: 'pnpm', args: ['-C', join(root, 'spark-ui'), 'run', 'ci'] },
  {
    name: 'spark-rooter',
    cmd: 'node',
    // 含单元测试：任一失败 surefire 让 install 非 0（backend-standard §1）
    args: [join(harness, 'scripts', 'mvn.mjs'), '-q', '-B', 'install'],
    skipIf: () => !existsSync(join(root, 'spark-rooter', 'pom.xml')),
  },
  {
    // 独立示例宿主：-o 离线证明只依赖本地仓（spec §6.4）
    name: 'host-demo',
    cmd: 'mvn',
    args: ['-q', '-B', '-o', 'package', '-DskipTests'],
    cwd: join(root, 'spark-rooter', 'examples', 'host-demo'),
    // 不用 clean 插件（离线可能未缓存），直接删 target 保证重打包（否则 boot repackage 可能沿用旧 jar）
    before: () => rmSync(join(root, 'spark-rooter', 'examples', 'host-demo', 'target'), { recursive: true, force: true }),
    skipIf: () => !existsSync(join(root, 'spark-rooter', 'examples', 'host-demo', 'pom.xml')),
  },
];

const results = [];
for (const s of steps) {
  if (s.skipIf?.()) {
    results.push([s.name, 'skipped']);
    console.log(`\n=== ${s.name}: skipped (not initialized) ===`);
    continue;
  }
  console.log(`\n=== ${s.name} ===`);
  s.before?.();
  const r = spawnSync(s.cmd, s.args, { stdio: 'inherit', cwd: s.cwd ?? root });
  const code = r.status ?? 1;
  results.push([s.name, code]);
  if (code !== 0) {
    console.error(`\nci: step "${s.name}" failed with exit ${code}`);
    summary();
    process.exit(code);
  }
}
summary();
console.log('ci: all steps passed (exit 0)');

function summary() {
  console.log('\n--- ci summary ---');
  for (const [n, c] of results) console.log(`${n.padEnd(18)} ${c}`);
}
