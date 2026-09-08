#!/usr/bin/env node
/**
 * pnpm -C .harness run check-module-deps
 *
 * 后端模块依赖红线（project-structure.md §2 / §4）：
 *   - agent-runtime、tool-registry、tool-gateway、platform-spi、contracts-java 的 pom.xml 不得依赖任何 domains/* 模块（只有 app 可以）
 *   - 任何模块 DDD 分层 domain/ 包（文件直接父目录为 domain）下的 .java 不得 import org.springframework.* 或 com.fasterxml.*
 *
 * backed/ 尚无 pom.xml 时视为通过（骨架未初始化）。
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const backed = join(root, 'backed');

let violations = 0;
const fail = (msg) => {
  console.error(`✗ ${msg}`);
  violations++;
};

if (!existsSync(join(backed, 'pom.xml'))) {
  console.log('✓ check-module-deps: backed/pom.xml not present, skipping');
  process.exit(0);
}

async function domainModuleArtifactIds() {
  const dir = join(backed, 'domains');
  if (!existsSync(dir)) return [];
  const ids = [];
  for (const m of await readdir(dir)) {
    const pom = join(dir, m, 'pom.xml');
    if (!existsSync(pom)) continue;
    const text = await readFile(pom, 'utf-8');
    // 模块自身的 artifactId 是 <parent> 块之后的第一个 <artifactId>，不能取全文第一个（那是父 POM 的）。
    const withoutParent = text.replace(/<parent>[\s\S]*?<\/parent>/, '');
    const match = withoutParent.match(/<artifactId>([^<]+)<\/artifactId>/);
    if (match) ids.push(match[1]);
  }
  return ids;
}

const domainIds = await domainModuleArtifactIds();
for (const mod of ['agent-runtime', 'tool-registry', 'tool-gateway', 'platform-spi', 'contracts-java']) {
  const pom = join(backed, mod, 'pom.xml');
  if (!existsSync(pom)) continue;
  const text = await readFile(pom, 'utf-8');
  // 只看 <dependencies> 内的 <dependency>，排除 <parent> 与 <dependencyManagement>
  const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
  for (const id of domainIds) {
    if (depsBlock.includes(`<artifactId>${id}</artifactId>`)) {
      fail(`${mod}/pom.xml depends on domain module "${id}" (control/decision plane must not touch domains)`);
    }
  }
}

// platform-spi 是最底层接口包：pom 不得依赖任何 com.strato artifact；contracts-java 只允许依赖 platform-spi
{
  const allowed = { 'platform-spi': [], 'contracts-java': ['platform-spi'] };
  for (const [mod, ok] of Object.entries(allowed)) {
    const pom = join(backed, mod, 'pom.xml');
    if (!existsSync(pom)) continue;
    const text = await readFile(pom, 'utf-8');
    const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
    for (const m of depsBlock.matchAll(/<groupId>com\.strato<\/groupId><artifactId>([^<]+)<\/artifactId>/g)) {
      if (!ok.includes(m[1])) fail(`${mod}/pom.xml must not depend on com.strato:${m[1]} (bottom layer)`);
    }
  }
}

async function* walk(dir) {
  for (const e of await readdir(dir)) {
    const p = join(dir, e);
    const s = await stat(p);
    if (s.isDirectory()) {
      if (e === 'target' || e === 'node_modules') continue;
      yield* walk(p);
    } else if (p.endsWith('.java')) yield p;
  }
}

// 全文匹配（含 FQN 内联引用与 import），不只看 import 行，避免 `org.springframework.stereotype.Service` 内联绕过
const banned = [/\borg\.springframework\./, /\bcom\.fasterxml\./];
for await (const file of walk(backed)) {
  // DDD 分层 domain/ 包的判定：src/main/java 之后的包路径中，去掉领域模块基础包 com/strato/domain/<svc>/ 这一段后，
  // 任一段等于 "domain"（含子包 domain/policy 等）。领域模块基础包里的 "domain" 是模块分组，不是分层包。
  const rel = relative(backed, file).split('/');
  const javaIdx = rel.indexOf('java');
  if (javaIdx < 0) continue;
  let pkg = rel.slice(javaIdx + 1, -1);
  if (pkg[0] === 'com' && pkg[1] === 'strato' && pkg[2] === 'domain') pkg = pkg.slice(4);
  else pkg = pkg.slice(3);
  if (!pkg.includes('domain')) continue;
  const text = await readFile(file, 'utf-8');
  for (const re of banned) {
    if (re.test(text)) {
      fail(`${relative(root, file)}: domain/ package must not reference ${re.source.replace(/\\\./g, '.').replace(/\\b/g, '')}`);
    }
  }
}

// 跨模块依赖红线（backend-standard §4）：runtime / registry / gateway 之间不得 import 对方的 infra 或 domain 包
const peers = { 'agent-runtime': ['registry', 'gateway'], 'tool-registry': ['runtime', 'gateway'], 'tool-gateway': ['runtime', 'registry'] };
for (const [mod, others] of Object.entries(peers)) {
  const src = join(backed, mod, 'src', 'main', 'java');
  if (!existsSync(src)) continue;
  for await (const file of walk(src)) {
    const text = await readFile(file, 'utf-8');
    for (const o of others) {
      // 三模块之间只允许依赖对方 api 包（project-structure §2）；infra / domain / application 都不行
      const re = new RegExp(`\\bcom\\.strato\\.${o}\\.(infra|domain|application)\\.`);
      if (re.test(text)) fail(`${relative(root, file)}: ${mod} must not depend on ${o}'s infra/domain/application package (only api)`);
    }
  }
}

// 领域模块互不依赖（project-structure §2）：domains/<a> 源码不得引用 com.strato.domain.<b>（b ≠ a），也不得在 pom 里依赖其他领域 artifact。
// 跨领域读数据只能经 platform-spi 端口（如 OrderSnapshotProvider）。
{
  const domainsDir = join(backed, 'domains');
  const mods = existsSync(domainsDir) ? await readdir(domainsDir) : [];
  for (const mod of mods) {
    const src = join(domainsDir, mod, 'src', 'main', 'java', 'com', 'strato', 'domain');
    if (!existsSync(src)) continue;
    const own = (await readdir(src)).filter((d) => !d.endsWith('.java'));
    const pom = join(domainsDir, mod, 'pom.xml');
    if (existsSync(pom)) {
      const depsBlock = ((await readFile(pom, 'utf-8')).match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
      for (const id of domainIds) {
        if (id !== mod && depsBlock.includes(`<artifactId>${id}</artifactId>`)) fail(`domains/${mod}/pom.xml depends on sibling domain "${id}" (domains must not depend on each other)`);
      }
    }
    for await (const file of walk(src)) {
      const text = await readFile(file, 'utf-8');
      for (const m of text.matchAll(/\bcom\.strato\.domain\.([a-z]+)\./g)) {
        if (!own.includes(m[1])) fail(`${relative(root, file)}: domains/${mod} must not reference com.strato.domain.${m[1]} (cross-domain only via platform-spi)`);
      }
    }
  }
}

if (violations > 0) {
  console.error(`\ncheck-module-deps: ${violations} violations`);
  process.exit(1);
}
console.log('✓ check-module-deps: backend module dependency direction OK');
