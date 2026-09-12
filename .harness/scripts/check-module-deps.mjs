#!/usr/bin/env node
/**
 * pnpm -C .harness run check-module-deps
 *
 * 后端模块依赖红线（project-structure.md §2 / §4）：
 *   - spark-rooter-runtime、spark-rooter-registry、spark-rooter-gateway、spark-rooter-spi、spark-rooter-contracts 的 pom.xml 不得依赖任何 examples/domains/* 模块（只有 examples/host-demo 可以）
 *   - 平台模块（spi / contracts / runtime / registry / gateway）pom 不得依赖 spring-boot-starter-web / starter-validation（Web 绑定只在 spark-rooter-web-mvc，spec refactor-spark-embedded-starter §2.2）
 *   - 平台模块（runtime / registry / gateway / web-mvc / contracts）源码禁 @Component / @Service / @Repository / @Configuration / @ComponentScan：Bean 全部由 starter @Bean 装配，不依赖包扫描
 *   - 任何模块 DDD 分层 domain/ 包（文件直接父目录为 domain）下的 .java 不得 import org.springframework.* 或 com.fasterxml.*
 *
 * spark-rooter/ 尚无 pom.xml 时视为通过（骨架未初始化）。
 */
import { readdir, readFile, stat } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const sparkRooterDir = join(root, 'spark-rooter');

// 以下三条正则被规则与自测共同引用（评审 v2 N-1：自测不能用手抄副本）
const BOTTOM_DEP = /<groupId>com\.sparkrooter<\/groupId><artifactId>([^<]+)<\/artifactId>/g;
const peerRule = (o) => new RegExp(`\\bcom\\.sparkrooter\\.${o}\\.(infra|domain|application)\\.`);
const EXAMPLES_REF = /\bcom\.sparkrooter\.examples\.([a-z]+)\./g;

let violations = 0;
const fail = (msg) => {
  console.error(`✗ ${msg}`);
  violations++;
};

if (!existsSync(join(sparkRooterDir, 'pom.xml'))) {
  console.log('✓ check-module-deps: spark-rooter/pom.xml not present, skipping');
  process.exit(0);
}

async function domainModuleArtifactIds() {
  const dir = join(sparkRooterDir, 'examples', 'domains');
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
for (const mod of ['spark-rooter-runtime', 'spark-rooter-registry', 'spark-rooter-gateway', 'spark-rooter-spi', 'spark-rooter-contracts']) {
  const pom = join(sparkRooterDir, mod, 'pom.xml');
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

// spark-rooter-spi 是最底层接口包：pom 不得依赖任何 com.sparkrooter artifact；spark-rooter-contracts 只允许依赖 spark-rooter-spi
{
  const allowed = { 'spark-rooter-spi': [], 'spark-rooter-contracts': ['spark-rooter-spi'] };
  for (const [mod, ok] of Object.entries(allowed)) {
    const pom = join(sparkRooterDir, mod, 'pom.xml');
    if (!existsSync(pom)) continue;
    const text = await readFile(pom, 'utf-8');
    const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
    for (const m of depsBlock.matchAll(BOTTOM_DEP)) {
      if (!ok.includes(m[1])) fail(`${mod}/pom.xml must not depend on com.sparkrooter:${m[1]} (bottom layer)`);
    }
  }
}

// 平台模块不绑 Web 容器：只有 spark-rooter-web-mvc 允许 spring-boot-starter-web / starter-validation
for (const mod of ['spark-rooter-spi', 'spark-rooter-contracts', 'spark-rooter-runtime', 'spark-rooter-registry', 'spark-rooter-gateway']) {
  const pom = join(sparkRooterDir, mod, 'pom.xml');
  if (!existsSync(pom)) continue;
  const text = await readFile(pom, 'utf-8');
  const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
  for (const banned of ['spring-boot-starter-web', 'spring-boot-starter-validation']) {
    if (depsBlock.includes(`<artifactId>${banned}</artifactId>`)) fail(`${mod}/pom.xml must not depend on ${banned} (web binding lives only in spark-rooter-web-mvc)`);
  }
}

// provider-starter 薄依赖（feat-provider-http-transport §3.3）：
// provider 装在别人的业务服务里，不该因为"声明了几个工具"就被拖进一个 LLM 客户端和整套 Agent Runtime。
// 也不绑 web 栈选型（只用编译期 spring-web，运行期容器由宿主已有的 starter 提供）。
{
  const mod = 'spark-provider-spring-boot-starter';
  const pom = join(sparkRooterDir, mod, 'pom.xml');
  if (existsSync(pom)) {
    const text = await readFile(pom, 'utf-8');
    const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
    const banned = [
      'spark-rooter-runtime', 'spark-rooter-registry', 'spark-rooter-gateway',
      'spark-rooter-web-mvc', 'spark-rooter-spring-boot-starter',
      'spring-boot-starter-web', 'spring-boot-starter-validation',
    ];
    for (const id of banned) {
      if (depsBlock.includes(`<artifactId>${id}</artifactId>`)) {
        fail(`${mod}/pom.xml must not depend on "${id}" (provider stays thin: no hub modules, no web stack)`);
      }
    }
    // Spring AI 的坐标以 spring-ai- 开头，逐个列举不如前缀匹配
    for (const m of depsBlock.matchAll(/<artifactId>(spring-ai-[\w-]+)<\/artifactId>/g)) {
      fail(`${mod}/pom.xml must not depend on "${m[1]}" (provider does not plan; the LLM client belongs to the hub)`);
    }
    // JDK 下限必须是 17：provider 跑在宿主的业务服务里，企业存量大量停在 17
    if (!/<maven\.compiler\.release>17<\/maven\.compiler\.release>/.test(text)) {
      fail(`${mod}/pom.xml must pin <maven.compiler.release>17</maven.compiler.release> (provider runs inside JDK 17 hosts)`);
    }
  }
}

// 共享契约层（spi / contracts）必须是 17：provider 加载 21 字节码会 UnsupportedClassVersionError
for (const mod of ['spark-rooter-spi', 'spark-rooter-contracts']) {
  const pom = join(sparkRooterDir, mod, 'pom.xml');
  if (!existsSync(pom)) continue;
  const text = await readFile(pom, 'utf-8');
  if (!/<maven\.compiler\.release>17<\/maven\.compiler\.release>/.test(text)) {
    fail(`${mod}/pom.xml must pin release 17 (shared with the provider, which may run on JDK 17)`);
  }
}

// provider 闭包的**产物**字节码必须 ≤ JDK 17（major 61）。
// pom 里的 release 属性只是意图，真正决定 provider 能否在 JDK 17 上加载的是 class 文件版本；
// 二者可能脱节（改了 parent 的 plugin 配置、加了未覆盖 release 的新模块）。已编译时才检查。
{
  const CLASS_MAJOR_JDK17 = 61;
  for (const mod of ['spark-rooter-spi', 'spark-rooter-contracts', 'spark-provider-spring-boot-starter']) {
    const classesDir = join(sparkRooterDir, mod, 'target', 'classes');
    if (!existsSync(classesDir)) continue; // 未构建时跳过，不把门禁变成"必须先 mvn"
    for await (const file of walkClasses(classesDir)) {
      const buf = await readFile(file);
      // class 文件头：magic(4) + minor(2) + major(2)
      const major = buf.readUInt16BE(6);
      if (major > CLASS_MAJOR_JDK17) {
        fail(`${relative(root, file)}: class major version ${major} > ${CLASS_MAJOR_JDK17} (JDK 17); a JDK 17 provider host would fail with UnsupportedClassVersionError`);
        break; // 一个模块报一次足够
      }
    }
  }
}

// 工具实现不得自行重试（评审 S-2）。
// ToolHandler 的 javadoc 早有这条文字约定但从无门禁；跨进程后后果被放大——provider 自己重试
// × hub 按 Manifest 重试 = 指数放大，在退款/扣款场景会造成多次重复执行。
// 只抓明显写法，不求完备：让「顺手加个 @Retryable」这类改动变红即可。
{
  // 简名与 FQN 内联都抓（`@org.springframework.retry.annotation.Retryable` 不能绕过），
  // 与 STEREOTYPES 同一手法。首版只写 `@Retryable\b`，自证时用 FQN 注入没变红。
  const RETRY =
    /@(?:org\.springframework\.retry\.annotation\.)?Retryable\b|RetryTemplate\b|for\s*\(\s*int\s+attempt\b/;
  const dirs = [];
  for (const mod of await readdir(join(sparkRooterDir, 'examples', 'domains')).catch(() => [])) {
    dirs.push(['examples/domains/' + mod, join(sparkRooterDir, 'examples', 'domains', mod, 'src', 'main', 'java')]);
  }
  dirs.push(['examples/provider-demo', join(sparkRooterDir, 'examples', 'provider-demo', 'src', 'main', 'java')]);
  for (const [label, src] of dirs) {
    if (!existsSync(src)) continue;
    for await (const file of walk(src)) {
      const text = await readFile(file, 'utf-8');
      if (RETRY.test(text)) {
        fail(`${relative(root, file)}: tool implementations must not retry themselves (the Gateway retries per Manifest; provider-side retry multiplies it — see backend-standard "Provider 侧约束")`);
      }
    }
  }
}

// hub 与 provider 的执行端点路径必须一致（阶段 4 评审 F-3）。
// 两个常量在不同模块，靠注释"必须一致"守不住；不一致的后果是 hub 静默 404。
{
  const hubFile = join(sparkRooterDir, 'spark-rooter-gateway', 'src', 'main', 'java',
    'com', 'sparkrooter', 'gateway', 'infra', 'transport', 'HttpToolTransport.java');
  const provFile = join(sparkRooterDir, 'spark-provider-spring-boot-starter', 'src', 'main', 'java',
    'com', 'sparkrooter', 'provider', 'ProviderInvokeController.java');
  if (existsSync(hubFile) && existsSync(provFile)) {
    const hub = (await readFile(hubFile, 'utf-8')).match(/INVOKE_PATH\s*=\s*"([^"]+)"/)?.[1];
    const base = (await readFile(provFile, 'utf-8')).match(/BASE_PATH\s*=\s*"([^"]+)"/)?.[1];
    if (!hub || !base) {
      fail('cannot read INVOKE_PATH / BASE_PATH constants (renamed?); the hub↔provider endpoint path check is now blind');
    } else if (hub !== base + '/invoke') {
      fail(`endpoint path mismatch: hub INVOKE_PATH="${hub}" but provider BASE_PATH="${base}" (expected "${base}/invoke") — the hub would 404 silently`);
    }
  }
}

// Micrometer 不得成为硬依赖（feat-runtime-limits-and-metrics T06）。
// 指标导出是可选能力：provider 形态与不用监控的宿主不该被迫引入。只有 hub starter 可以依赖它，
// 且**必须带 <optional>true</optional>** —— 不带 optional 会传递给所有宿主，那与"可选"名不符实。
{
  const MICROMETER_BANNED = [
    'spark-rooter-spi', 'spark-rooter-contracts', 'spark-rooter-runtime',
    'spark-rooter-registry', 'spark-rooter-gateway', 'spark-rooter-web-mvc',
    'spark-provider-spring-boot-starter',
  ];
  for (const mod of MICROMETER_BANNED) {
    const pom = join(sparkRooterDir, mod, 'pom.xml');
    if (!existsSync(pom)) continue;
    const text = await readFile(pom, 'utf-8');
    const depsBlock = (text.match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
    for (const m of depsBlock.matchAll(/<artifactId>(micrometer[\w-]*)<\/artifactId>/g)) {
      fail(`${mod}/pom.xml must not depend on "${m[1]}" (metrics export is optional; only the hub starter may depend on Micrometer, and only as <optional>true</optional>)`);
    }
  }
  // hub starter：可以有，但必须 optional
  const starterPom = join(sparkRooterDir, 'spark-rooter-spring-boot-starter', 'pom.xml');
  if (existsSync(starterPom)) {
    const text = await readFile(starterPom, 'utf-8');
    // 取 micrometer 那一条 <dependency> 块，检查它自身是否带 optional
    for (const m of text.matchAll(/<dependency>(?:(?!<\/dependency>)[\s\S])*?micrometer[\s\S]*?<\/dependency>/g)) {
      if (!/<optional>\s*true\s*<\/optional>/.test(m[0])) {
        fail('spark-rooter-spring-boot-starter/pom.xml: the Micrometer dependency must be <optional>true</optional>, otherwise it leaks transitively to every host (including providers and hosts that use another monitoring stack)');
      }
    }
  }
}

// 平台 Bean 不靠包扫描：平台模块源码不得出现 Spring 组件注解（starter 与 examples 除外）
// 简名与 FQN 内联都抓（`@org.springframework.stereotype.Service` 不能绕过）
const STEREOTYPES =
  /@(?:org\.springframework\.(?:stereotype|context\.annotation)\.)?(Component|Service|Repository|Configuration|ComponentScan)\b/;
// 身份归宿主（spec §2.5）：平台模块源码禁 userId / tenantId / Principal 标识符
const IDENTITY = /\b(userId|tenantId|Principal)\b/;
// 领域知识归宿主注解（spec refactor-llm-planner-domain-free）：平台模块源码禁示例领域词汇与 toolId 片段
const DOMAIN_WORDS = /订单|商品|退款|售后|物流|\b(order|product|refund|aftersale)\.[a-z]+\.[a-z]+\b/;
const PLATFORM_SRC = ['spark-rooter-spi', 'spark-rooter-contracts', 'spark-rooter-runtime', 'spark-rooter-registry', 'spark-rooter-gateway', 'spark-rooter-web-mvc', 'spark-rooter-spring-boot-starter', 'spark-provider-spring-boot-starter'];

/** 递归产出 .class 文件（字节码版本检查用）。 */
async function* walkClasses(dir) {
  for (const e of await readdir(dir)) {
    const p = join(dir, e);
    const s = await stat(p);
    if (s.isDirectory()) yield* walkClasses(p);
    else if (p.endsWith('.class')) yield p;
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

for (const mod of PLATFORM_SRC) {
  const src = join(sparkRooterDir, mod, 'src', 'main', 'java');
  if (!existsSync(src)) continue;
  for await (const file of walk(src)) {
    const text = await readFile(file, 'utf-8');
    if (!mod.endsWith('-spring-boot-starter') && STEREOTYPES.test(text)) fail(`${relative(root, file)}: platform module must not use Spring stereotype annotations (beans are assembled by the starter)`);
    if (IDENTITY.test(text)) fail(`${relative(root, file)}: platform module must not reference userId / tenantId / Principal (identity belongs to the host)`);
    if (DOMAIN_WORDS.test(text)) fail(`${relative(root, file)}: platform module must not contain domain vocabulary (order/product/refund/aftersale words or toolIds belong to host annotations)`);
  }
}

// 规则自测（Hashimoto）：引用与规则**同一个**正则对象；正样本必须命中、反样本（旧包名）必须不命中，改名后正则漂移会直接红
const fresh = (re) => new RegExp(re.source, re.flags.replace('g', ''));
const selfTests = [
  [fresh(STEREOTYPES), '@org.springframework.stereotype.Service\npublic class X {}', 'stereo-fqn'],
  [fresh(STEREOTYPES), '  @Component\npublic class X {}', 'stereo-simple'],
  [fresh(IDENTITY), 'String tenantId', 'identity'],
  [fresh(DOMAIN_WORDS), '"order.list.search"', 'domain-toolid'],
  [fresh(DOMAIN_WORDS), '「订单」', 'domain-word'],
  [peerRule('gateway'), 'import com.sparkrooter.gateway.infra.LogAuditSink;', 'peer'],
  [fresh(EXAMPLES_REF), 'import com.sparkrooter.examples.order.domain.Order;', 'examples'],
  [fresh(BOTTOM_DEP), '<dependency><groupId>com.sparkrooter</groupId><artifactId>spark-rooter-runtime</artifactId></dependency>', 'bottom-dep'],
];
for (const [re, sample, name] of selfTests) {
  if (!re.test(sample)) fail(`check-module-deps self-test[${name}]: rule does not match its positive sample`);
}
const negatives = [
  [peerRule('gateway'), 'import com.sparkrooter.gateway.api.ToolInvokePort;', 'peer-api-allowed'],
  [fresh(EXAMPLES_REF), 'import com.spark.domain.order.Order;', 'examples-old-name'],
  [fresh(BOTTOM_DEP), '<groupId>com.spark</groupId><artifactId>x</artifactId>', 'bottom-old-group'],
  [fresh(IDENTITY), 'String sessionId', 'identity-session-ok'],
];
for (const [re, sample, name] of negatives) {
  if (re.test(sample)) fail(`check-module-deps self-test[${name}]: rule wrongly matches its negative sample`);
}

// 全文匹配（含 FQN 内联引用与 import），不只看 import 行，避免 `org.springframework.stereotype.Service` 内联绕过
const banned = [/\borg\.springframework\./, /\bcom\.fasterxml\./];
for await (const file of walk(sparkRooterDir)) {
  // DDD 分层 domain/ 包的判定：src/main/java 之后的包路径中，去掉领域模块基础包 com/sparkrooter/examples/<svc>/ 这一段后，
  // 任一段等于 "domain"（含子包 domain/policy 等）。领域模块基础包里的 "domain" 是模块分组，不是分层包。
  const rel = relative(sparkRooterDir, file).split('/');
  const javaIdx = rel.indexOf('java');
  if (javaIdx < 0) continue;
  let pkg = rel.slice(javaIdx + 1, -1);
  if (pkg[0] === 'com' && pkg[1] === 'sparkrooter' && pkg[2] === 'examples') pkg = pkg.slice(4);
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
const peers = { 'spark-rooter-runtime': ['registry', 'gateway'], 'spark-rooter-registry': ['runtime', 'gateway'], 'spark-rooter-gateway': ['runtime', 'registry'] };
for (const [mod, others] of Object.entries(peers)) {
  const src = join(sparkRooterDir, mod, 'src', 'main', 'java');
  if (!existsSync(src)) continue;
  for await (const file of walk(src)) {
    const text = await readFile(file, 'utf-8');
    for (const o of others) {
      // 三模块之间只允许依赖对方 api 包（project-structure §2）；infra / domain / application 都不行
      if (peerRule(o).test(text)) fail(`${relative(root, file)}: ${mod} must not depend on ${o}'s infra/domain/application package (only api)`);
    }
  }
}

// 示例领域模块只依赖 spi / contracts（+ demo-support），不得依赖任何平台模块（spec refactor-spark-embedded-starter §2.2）
{
  const domainsDir = join(sparkRooterDir, 'examples', 'domains');
  const platform = ['spark-rooter-runtime', 'spark-rooter-registry', 'spark-rooter-gateway', 'spark-rooter-web-mvc', 'spark-rooter-spring-boot-starter'];
  for (const mod of existsSync(domainsDir) ? await readdir(domainsDir) : []) {
    const pom = join(domainsDir, mod, 'pom.xml');
    if (!existsSync(pom)) continue;
    const depsBlock = ((await readFile(pom, 'utf-8')).match(/<dependencies>([\s\S]*?)<\/dependencies>/g) ?? []).join('\n');
    for (const id of platform) {
      if (depsBlock.includes(`<artifactId>${id}</artifactId>`)) fail(`examples/domains/${mod}/pom.xml must not depend on platform module "${id}" (domains only see spi / contracts)`);
    }
  }
}

// 领域模块互不依赖（project-structure §2）：domains/<a> 源码不得引用 com.sparkrooter.examples.<b>（b ≠ a），也不得在 pom 里依赖其他领域 artifact。
// 跨领域读数据只能经 demo-support 的 OrderSnapshotProvider 端口。
{
  const domainsDir = join(sparkRooterDir, 'examples', 'domains');
  const mods = existsSync(domainsDir) ? await readdir(domainsDir) : [];
  for (const mod of mods) {
    const src = join(domainsDir, mod, 'src', 'main', 'java', 'com', 'sparkrooter', 'examples');
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
      for (const m of text.matchAll(EXAMPLES_REF)) {
        // examples.support（demo-support：mock 用户上下文 + OrderSnapshot 跨域只读端口）是共享支撑模块，不是兄弟领域
        if (m[1] === 'support') continue;
        if (!own.includes(m[1])) fail(`${relative(root, file)}: domains/${mod} must not reference com.sparkrooter.examples.${m[1]} (cross-domain only via spark-rooter-spi)`);
      }
    }
  }
}

if (violations > 0) {
  console.error(`\ncheck-module-deps: ${violations} violations`);
  process.exit(1);
}
console.log('✓ check-module-deps: backend module dependency direction OK');
