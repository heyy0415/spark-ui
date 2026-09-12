#!/usr/bin/env node
/**
 * pnpm -C .harness run check-log-assertions
 *
 * e2e-backend.sh 的 grep 型断言引用的日志片段，必须在 spark-rooter 的 src/main 里真实存在。
 *
 * 为什么需要它：`refactor-llm-planner-domain-free` 删掉规则规划器时，连带删了 `source=memory` /
 * `route runId=… source=` 等日志，但四条依赖它们的断言留在脚本里，连续三个 change 无人发现——
 * 因为无模型环境下它们混在大批「模型相关失败」里。本脚本让这类断言在日志消失的当下就红。
 *
 * 设计边界（由原型实测决定，见 change ci-github-actions-pipeline 的 spec §2.4）：
 *   - 匹配源是**全部字符串字面量**，不是「log.xxx( 调用」。Java 格式化常把格式串换到下一行
 *     （RunOrchestrator 的 "decision runId={} …"、LogAuditSink 的 "audit runId={} …" 都是），
 *     按 log 调用匹配会漏掉它们。
 *   - 片段形如 `key=value` 时**只校验 key= 部分**。value 侧来源太杂：`status=replayed` 的值是
 *     运行时拼的字面量，`status=succeeded` 的值来自枚举 .name()，源码里没有这个字符串。
 *     本脚本要防的那类问题（key 侧整体消失）只需校验 key 即可。
 *
 * 退出码 0 = 全部片段有对应日志。
 */
import { readFile, readdir } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { join, dirname, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const root = join(__dirname, '..', '..');
const sparkRooter = join(root, 'spark-rooter');
const script = join(root, '.harness', 'scripts', 'e2e-backend.sh');

let errors = 0;
const fail = (msg) => {
  console.error(`✗ ${msg}`);
  errors++;
};

/**
 * 不来自 Java 日志的片段：每项必须注明原因。命中时会打印，让白名单在 CI 日志里持续可见，
 * 而不是静默跳过——白名单本身是校验强度的缺口，不该变成「加进去就不用管」的逃逸口。
 */
const ALLOWLIST = [
  ['run.completed', 'SSE 事件名，来自契约 sse-events.schema.json，不是日志'],
  ['confirmation.required', '同上'],
  ['run.failed', '同上'],
  ['ERROR', '日志级别本身，由日志框架输出'],
  ['请选择', 'UI 文案，来自 ui-schema 的 message.delta 内容'],
  ['退钱', 'e2e 发送的用户原话，不是日志'],
  ['session mismatch', '拒绝原因文案，在 RunOrchestrator 里但与 runId= 拼接后才成句'],
  ['Application run failed', 'Spring Boot 框架自身的启动失败日志，不在本仓源码内'],
  ['SelfCheckRunner', 'logger 名（类名），出现在日志行的 logger 字段而非消息内容'],
];

async function* walkJava(dir) {
  for (const e of await readdir(dir, { withFileTypes: true })) {
    if (e.name === 'target' || e.name === 'node_modules') continue;
    const p = join(dir, e.name);
    if (e.isDirectory()) {
      yield* walkJava(p);
    } else if (e.name.endsWith('.java') && p.includes(`${'src'}/main/`)) {
      yield p;
    }
  }
}

/** 收集 src/main 下所有 Java 字符串字面量（每行独立提取，跨行格式串因此不受影响）。 */
async function collectLiterals() {
  const literals = [];
  for await (const file of walkJava(sparkRooter)) {
    const text = await readFile(file, 'utf-8');
    for (const m of text.matchAll(/"([^"\\]{2,300})"/g)) {
      literals.push(m[1]);
    }
  }
  return literals;
}

/**
 * 从 grep 参数里剥出可校验的固定文本：去掉 $VAR / $(...) 插值与正则元字符，
 * 再取最长的连续片段。返回空串表示整个参数都是插值，无可校验内容。
 */
function fixedPart(raw) {
  const stripped = raw
    .replace(/\$\([^)]*\)/g, '\u0000')
    .replace(/\$\{[^}]*\}/g, '\u0000')
    .replace(/\$[A-Za-z_][A-Za-z0-9_]*/g, '\u0000')
    .replace(/\.\*/g, '\u0000')
    .replace(/[\^$]/g, '\u0000');
  const segments = stripped.split('\u0000').map((s) => s.trim());
  return segments.sort((a, b) => b.length - a.length)[0] ?? '';
}

/** key=value → key=；其余原样。 */
function keyPart(fragment) {
  const m = fragment.match(/^(.*?[A-Za-z0-9_]+=)/);
  return m ? m[1] : fragment;
}

function isAllowed(fragment) {
  return ALLOWLIST.find(([needle]) => fragment.includes(needle));
}

/**
 * 片段是否被某条格式串覆盖：把格式串的 `{}` 当通配符。
 *
 * 断言里的片段是**运行后的日志文本**（如 `spark-rooter: 14 tools registered from 6 beans`），
 * 而源码里是**格式串**（`spark-rooter: {} tools registered from {} beans`）——数字由运行时填入。
 * 所以不能直接 includes，要把 `{}` 转成 `.*` 再匹配。
 */
function coveredByFormat(fragment, literals) {
  if (literals.some((l) => l.includes(fragment))) return true;
  return literals.some((lit) => {
    if (!lit.includes('{}')) return false;
    const pattern = lit
      .split('{}')
      .map((part) => part.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'))
      .join('[\\s\\S]*?');
    try {
      return new RegExp(pattern).test(fragment);
    } catch {
      return false;
    }
  });
}

const literals = await collectLiterals();
if (literals.length === 0) {
  fail('collected 0 string literals from spark-rooter/**/src/main — walker or path is wrong');
}

// 自测（Hashimoto）：规则漂移时直接红。正样本专门覆盖「跨行格式串」这个已踩过的坑。
{
  const mustFind = ['decision runId=', 'audit runId=', 'spark-rooter: ', 'selfcheck: '];
  const mustNotFind = ['source=memory', 'route runId=', 'source=model'];
  for (const s of mustFind) {
    if (!literals.some((l) => l.includes(s))) {
      fail(`self-test: expected to find "${s}" among Java literals but did not — matching source likely regressed to single-line log calls`);
    }
  }
  for (const s of mustNotFind) {
    if (literals.some((l) => l.includes(s))) {
      fail(`self-test: "${s}" should NOT exist in src/main (it was deleted with the rule-based planner) — either it came back or the matcher is too loose`);
    }
  }
  // fixedPart / keyPart 的行为也自测，避免正则改坏后静默放过一切
  const cases = [
    ['decision runId=$R3 kind=Planned planner=', 'kind=Planned planner='],
    ['runId=$(runid_of c20) .*source=memory', 'source=memory'],
    ['$LLM_HOST', ''],
    ['spark-rooter: 14 tools registered from 6 beans', 'spark-rooter: 14 tools registered from 6 beans'],
  ];
  for (const [input, expected] of cases) {
    const got = fixedPart(input);
    if (got !== expected) {
      fail(`self-test: fixedPart("${input}") = "${got}", expected "${expected}"`);
    }
  }
  if (keyPart('status=replayed') !== 'status=') {
    fail(`self-test: keyPart("status=replayed") = "${keyPart('status=replayed')}", expected "status="`);
  }
  // {} 通配：断言里是运行后的文本，源码里是格式串。漏了这条会把「带运行时数值的日志」全判为不存在。
  const fmt = ['spark-rooter: {} tools registered from {} beans'];
  if (!coveredByFormat('spark-rooter: 14 tools registered from 6 beans', fmt)) {
    fail('self-test: coveredByFormat should treat {} as a wildcard');
  }
  if (coveredByFormat('spark-rooter: 14 widgets registered from 6 beans', fmt)) {
    fail('self-test: coveredByFormat must not match when the fixed parts differ');
  }
}

const text = await readFile(script, 'utf-8');
// grep -c / -q / -rl，可带 -- 分隔符，参数为单引号或双引号包裹
const GREP_ARG = /grep\s+-[a-zA-Z]*[cqlr][a-zA-Z]*\s+(?:--\s+)?(["'])((?:(?!\1).)+)\1/g;

const checked = [];
const allowedHits = [];
for (const m of text.matchAll(GREP_ARG)) {
  const raw = m[2];
  const fixed = fixedPart(raw);
  if (fixed.length < 4) continue; // 纯插值或过短，无可校验内容
  const allowed = isAllowed(fixed);
  if (allowed) {
    allowedHits.push([fixed, allowed[1]]);
    continue;
  }
  // 先按整段（含 {} 通配）匹配格式串；不中再退一步只校验 key= 部分
  if (coveredByFormat(fixed, literals)) {
    checked.push(fixed);
    continue;
  }
  const needle = keyPart(fixed);
  if (!literals.some((l) => l.includes(needle))) {
    fail(`e2e-backend.sh greps for "${fixed}" but no Java string literal in src/main contains "${needle}" — the log line it asserts on probably no longer exists`);
  } else {
    checked.push(needle);
  }
}

if (allowedHits.length > 0) {
  console.log(`allowlisted (not from Java logs):`);
  for (const [frag, reason] of [...new Map(allowedHits).entries()]) {
    console.log(`  - "${frag}" — ${reason}`);
  }
}

if (!existsSync(script)) {
  fail(`script not found: ${relative(root, script)}`);
}

console.log('');
if (errors > 0) {
  console.error(`check-log-assertions: ${errors} errors`);
  process.exit(1);
}
console.log(
  `check-log-assertions: ${new Set(checked).size} distinct log fragments verified against ${literals.length} literals`,
);
