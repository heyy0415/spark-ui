#!/usr/bin/env node
/**
 * pnpm -C .harness run ci
 *
 * 全仓单一质量门禁（在仓库根或 .harness/ 下执行均可）。按顺序：
  *   0. check-rename         —— 旧项目名 / 旧顶层目录名残留（见脚本头注释）
 *   1. check-contracts      —— .harness/contracts/ Schema 与示例
 *   2. check-module-deps    —— 后端模块依赖红线
 *   3. check-seed            —— 四领域 mock 种子与 DDL 一致
 *   4. check-log-assertions  —— e2e 断言引用的日志片段在 src/main 中真实存在
 *   5. check-shell           —— shellcheck（本机未装则跳过；CI 必跑）
 *   6. spark-ui              —— pnpm -C spark-ui run ci（typecheck + 单测 + lint + format:check + build + verify-pack）
 *   7. spark-rooter          —— ./mvnw -q -B install（编译 + JUnit 单测 + spotless + 进本地仓；pom.xml 不存在时跳过）
 *   8. host-demo             —— examples/host-demo mvn -q -o package（离线，只依赖本地仓）
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
  {
    name: 'check-log-assertions',
    cmd: 'node',
    args: [join(harness, 'scripts', 'check-log-assertions.mjs')],
  },
  {
    // bash -n 查不出的问题（变量名被全角字符吞掉、未加引号的词分割）由 shellcheck 拦住。
    // 本机未装时跳过并提示——不因缺工具而红，但 CI 镜像自带 shellcheck，那里必跑。
    name: 'check-shell',
    cmd: 'shellcheck',
    args: [
      join(harness, 'scripts', 'e2e-backend.sh'),
      join(harness, 'scripts', 'e2e-frontend.sh'),
      join(harness, 'scripts', 'deploy-verify.sh'),
      join(harness, 'scripts', 'lib', 'change-dir.sh'),
      join(harness, 'scripts', 'lib', 'java-home.sh'),
    ],
    skipIf: () => {
      const probe = spawnSync('shellcheck', ['--version'], { stdio: 'ignore' });
      if (probe.error) {
        console.log('  (shellcheck not installed — `brew install shellcheck` to run it locally)');
        return true;
      }
      return false;
    },
  },
  { name: 'spark-ui', cmd: 'pnpm', args: ['-C', join(root, 'spark-ui'), 'run', 'ci'] },
  {
    name: 'spark-rooter',
    cmd: 'node',
    // 含单元测试：任一失败 surefire 让 install 非 0（backend-standard §1）
    args: [join(harness, 'scripts', 'mvn.mjs'), '-q', '-B', 'install'],
    skipIf: () => !existsSync(join(root, 'spark-rooter', 'pom.xml')),
  },
  {
    // 独立示例宿主：-o 离线证明只依赖本地仓（spec §6.4）。
    // 这一步直接调 mvn（不经 mvn.mjs，后者的 cwd 固定在 spark-rooter/），所以要自己保证 JDK 21 —
    // CI 上 JAVA_HOME 由 actions/setup-java 提供，本机回落到与 mvn.mjs / java-home.sh 一致的候选路径。
    name: 'host-demo',
    cmd: 'mvn',
    args: ['-q', '-B', '-o', 'package', '-DskipTests'],
    cwd: join(root, 'spark-rooter', 'examples', 'host-demo'),
    env: () => {
      const e = { ...process.env };
      const candidates = [
        e.JAVA_HOME,
        `${e.HOME}/.jenv/versions/21`,
        `${e.HOME}/.jenv/versions/openjdk64-21.0.11`,
        '/Library/Java/JavaVirtualMachines/openjdk-21.jdk/Contents/Home',
      ].filter(Boolean);
      const found = candidates.find((c) => existsSync(join(c, 'bin', 'java')));
      if (found) {
        e.JAVA_HOME = found;
        e.PATH = `${join(found, 'bin')}:${e.PATH}`;
      }
      return e;
    },
    // 不用 clean 插件（离线可能未缓存），直接删 target 保证重打包（否则 boot repackage 可能沿用旧 jar）
    before: () => rmSync(join(root, 'spark-rooter', 'examples', 'host-demo', 'target'), { recursive: true, force: true }),
    skipIf: () => !existsSync(join(root, 'spark-rooter', 'examples', 'host-demo', 'pom.xml')),
  },
];

const results = [];
for (const s of steps) {
  console.log(`\n=== ${s.name} ===`);
  if (s.skipIf?.()) {
    results.push([s.name, 'skipped']);
    console.log(`  skipped`);
    continue;
  }
  s.before?.();
  const r = spawnSync(s.cmd, s.args, {
    stdio: 'inherit',
    cwd: s.cwd ?? root,
    env: s.env ? s.env() : process.env,
  });
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
