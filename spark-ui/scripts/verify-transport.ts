/**
 * pnpm -C spark-ui run verify-transport（vite-node）
 *
 * 传输层回归门禁（评审 M-1 / N-1）。用假 fetch 捕获 URL，不起服务。守两层不变量：
 *
 * 1. `@spark-ui/core/client`：`request()` 与 `consumeSse()` 的请求 URL 必须带 `baseUrl` 前缀，
 *    且 `''` 必须被当作「显式同源」而不是空值。core 是 headless 的，不读 `import.meta.env`
 *    （check-deps 有硬门禁），所以 `baseUrl` 在类型上必填——回落策略归宿主。
 * 2. 宿主（apps/chat）：缺省 `baseUrl` 时回落 `env.VITE_API_BASE_URL` 而不是写死 `''`，
 *    否则会短路 env 配置（评审 M-1 的原始命题，现在的落点在 AgentChatPanel）。
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { consumeSse, ErrorResponseSchema, request } from '@spark-ui/core/client';

let failed = 0;
const check = (name: string, expected: string, actual: string) => {
  if (expected === actual) {
    process.stdout.write(`✓ ${name}\n`);
  } else {
    failed += 1;
    process.stderr.write(`✗ ${name}: expected [${expected}] got [${actual}]\n`);
  }
};

const seen: string[] = [];
const BODY = JSON.stringify({ code: 'NOT_FOUND', message: 'x', traceId: 'trace_1' });
const fakeFetch: typeof fetch = async (input) => {
  seen.push(String(input));
  return new Response(BODY, {
    status: 200,
    headers: { 'Content-Type': 'application/json' },
  });
};
const fakeSse: typeof fetch = async (input) => {
  seen.push(String(input));
  return new Response('event: run.completed\ndata: {}\n\n', { status: 200 });
};

await request('/agent/runs/run_1', {
  schema: ErrorResponseSchema,
  fetch: fakeFetch,
  baseUrl: 'https://gw.example.com',
});
check(
  'request() prefixes explicit baseUrl',
  'https://gw.example.com/agent/runs/run_1',
  seen.at(-1) ?? '',
);

await request('/agent/runs/run_2', {
  schema: ErrorResponseSchema,
  fetch: fakeFetch,
  baseUrl: '',
});
check("request() with baseUrl '' stays same-origin", '/agent/runs/run_2', seen.at(-1) ?? '');

await consumeSse(
  { path: '/agent/runs', body: {}, fetch: fakeSse, baseUrl: 'https://gw.example.com' },
  () => {},
);
check(
  'consumeSse() prefixes explicit baseUrl',
  'https://gw.example.com/agent/runs',
  seen.at(-1) ?? '',
);

// 空串必须被当作显式 baseUrl（同源），不能被 ?? 吞掉；而 undefined 在 core 里是类型错误 —— 两者语义不同
await consumeSse({ path: '/agent/runs', body: {}, fetch: fakeSse, baseUrl: '' }, () => {});
check("consumeSse() with baseUrl '' stays same-origin", '/agent/runs', seen.at(-1) ?? '');

// 宿主侧：env 回落必须落在 AgentChatPanel 而不是被写死 ''（评审 M-1）。
// 这里做源码级断言而非渲染断言：门禁脚本不引入 React 运行时，只保证这行不会被"顺手简化"掉。
const panelSource = readFileSync(
  fileURLToPath(
    new URL('../apps/chat/src/features/agent-chat/ui/AgentChatPanel.tsx', import.meta.url),
  ),
  'utf8',
);
check(
  'host falls back to env.VITE_API_BASE_URL when baseUrl is omitted',
  'present',
  panelSource.includes('baseUrl ?? env.VITE_API_BASE_URL') ? 'present' : 'missing',
);

process.stdout.write(`verify-transport: ${failed === 0 ? 'OK' : `${failed} failed`}\n`);
process.exit(failed > 0 ? 1 : 0);
