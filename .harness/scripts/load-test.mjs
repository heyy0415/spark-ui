#!/usr/bin/env node
/**
 * node .harness/scripts/load-test.mjs [--port 8080] [--concurrency 32] [--duration 20] [--message "看看我的订单"]
 *
 * 对 spark hub 做并发压测：N 路并发反复 POST /agent/runs 并把 SSE 读到终态，持续 D 秒，输出：
 *   total / ok / rejected（过载拒绝：run.failed 且文案含「请稍后重试」）/ failed（其他失败）/ transport error
 *   latency p50 / p95 / p99 / max（从发出请求到收到终态帧）
 *
 * 用途是给 spark.runtime.run-queue / spark.gateway.tool-queue / max-concurrent-per-session 这几个默认值
 * 一个实测依据，而不是推算。只依赖 Node 20 原生 fetch，不引 k6 / jmeter。
 *
 * 打的是 fake planner（e2e profile）：规划毫秒级，测出来的是编排 / 网关 / 线程池的容量，不含模型延迟。
 * 接真模型时把 --duration 调大、并发调小，否则大部分请求会在模型侧排队而不是 hub 侧。
 *
 * 退出码始终 0：它是量具不是门禁。结论由人读报告后写进 javadoc / README。
 *
 * 注意本机压测的两个失真源：
 *   - SSE 连接读到终态后由客户端 cancel，不能复用；数千 rps 下短连接风暴会堆出大量 TIME_WAIT，
 *     macOS 默认临时端口只有 ~1.6 万个，耗尽后 connect() 会卡住——服务端毫无痕迹，看起来像"某个请求慢"。
 *     报告里 wall 远大于 --duration 且 client_timeout > 0 时先怀疑这个，再怀疑服务端。
 *   - 独立会话与同会话测的是两个东西：前者是整机容量（编排池 / 工具池），后者是单会话上限（--same-session）。
 */
import { performance } from 'node:perf_hooks';

const args = Object.fromEntries(
  process.argv
    .slice(2)
    .join(' ')
    .split(/\s+--/)
    .filter(Boolean)
    .map((kv) => {
      const [k, ...rest] = kv.replace(/^--/, '').split(/\s+/);
      return [k, rest.join(' ') || 'true'];
    }),
);
const PORT = Number(args.port ?? 8080);
const CONCURRENCY = Number(args.concurrency ?? 32);
const DURATION_S = Number(args.duration ?? 20);
const MESSAGE = args.message ?? '看看我的订单';
const BASE = `http://127.0.0.1:${PORT}`;
const CAP = { uiSchemaVersion: '1.0', components: ['Form', 'Card', 'Table', 'Result', 'Timeline'] };
// 每路并发一个独立 conversationId：单会话并发上限（max-concurrent-per-session）是按会话算的，
// 全部打同一个会话测出来的是那个上限而不是整机容量。要测单会话上限就把 --same-session 打开
const SAME_SESSION = args['same-session'] === 'true';

const OVERLOADED_TEXT = '请稍后重试';
const CLIENT_TIMEOUT_MS = Number(args['client-timeout-ms'] ?? 100_000);

/** 一次完整的 Run：POST → 读 SSE 到终态。返回 {outcome, ms}。 */
async function oneRun(convId) {
  const t0 = performance.now();
  let res;
  // 客户端硬超时：服务端 sseTimeout 默认 90s，这里给 100s。量具自己不能挂死——一条卡住的连接会让整轮 Promise.all
  // 等到它为止，报告里的 durationS 就成了谎言。超时记 client_timeout，单列统计
  const ac = AbortSignal.timeout(CLIENT_TIMEOUT_MS);
  try {
    res = await fetch(`${BASE}/agent/runs`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-Trace-Id': 'trace_load' },
      body: JSON.stringify({ conversationId: convId, message: MESSAGE, clientCapabilities: CAP }),
      signal: ac,
    });
  } catch (e) {
    return { outcome: e?.name === 'TimeoutError' ? 'client_timeout' : 'transport', ms: performance.now() - t0 };
  }
  if (!res.ok || !res.body) {
    return { outcome: `http_${res.status}`, ms: performance.now() - t0 };
  }
  const reader = res.body.getReader();
  const dec = new TextDecoder();
  let buf = '';
  let outcome = 'no_terminal';
  outer: while (true) {
    let chunk;
    try {
      chunk = await reader.read();
    } catch (e) {
      outcome = e?.name === 'TimeoutError' || e?.name === 'AbortError' ? 'client_timeout' : 'transport';
      break;
    }
    const { value, done } = chunk;
    if (done) break;
    buf += dec.decode(value, { stream: true });
    let idx;
    while ((idx = buf.indexOf('\n\n')) >= 0) {
      const frame = buf.slice(0, idx);
      buf = buf.slice(idx + 2);
      const ev = /^event:\s?(.+)$/m.exec(frame)?.[1];
      if (ev === 'run.completed' || ev === 'confirmation.required') {
        outcome = 'ok';
        break outer;
      }
      if (ev === 'run.failed') {
        const data = /^data:\s?(.+)$/m.exec(frame)?.[1] ?? '';
        outcome = data.includes(OVERLOADED_TEXT) ? 'rejected' : 'failed';
        break outer;
      }
    }
  }
  try {
    await reader.cancel();
  } catch {
    // 连接已由服务端关闭
  }
  return { outcome, ms: performance.now() - t0 };
}

function pct(sorted, p) {
  if (sorted.length === 0) return 0;
  const i = Math.min(sorted.length - 1, Math.floor((p / 100) * sorted.length));
  return sorted[i];
}

async function main() {
  // 探活：后端没起就别测
  const health = await fetch(`${BASE}/actuator/health`).then((r) => r.ok).catch(() => false);
  if (!health) {
    console.error(`backend not healthy at ${BASE}; start host-demo first`);
    process.exit(2);
  }
  console.log(
    `load-test: port=${PORT} concurrency=${CONCURRENCY} duration=${DURATION_S}s message="${MESSAGE}" sameSession=${SAME_SESSION}`,
  );
  const wall0 = performance.now();
  const deadline = wall0 + DURATION_S * 1000;
  const results = [];
  let firstRejectAt = -1;
  const workers = Array.from({ length: CONCURRENCY }, async (_, w) => {
    let n = 0;
    while (performance.now() < deadline) {
      const conv = SAME_SESSION ? 'conv_load' : `conv_load_${w}_${n++}`;
      const r = await oneRun(conv);
      results.push(r);
      if (r.outcome === 'rejected' && firstRejectAt < 0) firstRejectAt = results.length;
    }
  });
  await Promise.all(workers);

  const by = (k) => results.filter((r) => r.outcome === k).length;
  const lat = results
    .filter((r) => r.outcome === 'ok')
    .map((r) => r.ms)
    .sort((a, b) => a - b);
  const other = results.filter((r) => !['ok', 'rejected', 'failed', 'transport', 'client_timeout'].includes(r.outcome));
  const wallS = (performance.now() - wall0) / 1000;
  const rps = (results.length / wallS).toFixed(1);
  console.log('');
  console.log(`total=${results.length}  wall=${wallS.toFixed(1)}s  rps≈${rps}`);
  if (wallS > DURATION_S * 1.5) {
    console.log(`  ⚠ wall time far exceeds --duration: some request hung until client timeout; see client_timeout below`);
  }
  console.log(`ok=${by('ok')}  rejected(overload)=${by('rejected')}  failed=${by('failed')}  transport=${by('transport')}  client_timeout=${by('client_timeout')}  other=${other.length}`);
  if (other.length) console.log(`  other outcomes: ${[...new Set(other.map((r) => r.outcome))].join(', ')}`);
  console.log(
    `latency(ok) ms: p50=${pct(lat, 50).toFixed(0)}  p95=${pct(lat, 95).toFixed(0)}  p99=${pct(lat, 99).toFixed(0)}  max=${(lat.at(-1) ?? 0).toFixed(0)}`,
  );
  console.log(`first rejection at request #${firstRejectAt < 0 ? 'none' : firstRejectAt}`);
  // 机器可读一行，供脚本汇总
  console.log(
    `RESULT ${JSON.stringify({ concurrency: CONCURRENCY, durationS: DURATION_S, wallS: Math.round(wallS), total: results.length, ok: by('ok'), rejected: by('rejected'), failed: by('failed'), transport: by('transport'), clientTimeout: by('client_timeout'), p50: Math.round(pct(lat, 50)), p95: Math.round(pct(lat, 95)), p99: Math.round(pct(lat, 99)), max: Math.round(lat.at(-1) ?? 0) })}`,
  );
}

main().catch((e) => {
  console.error(e);
  process.exit(1);
});
