/**
 * pnpm -C spark-ui run verify-transport（vite-node）
 *
 * 传输层回归门禁（评审 M-1 / N-1）：request() 与 consumeSse() 的请求 URL 必须带 baseUrl 前缀；缺省 baseUrl 时回落 env.VITE_API_BASE_URL
 * 而不是 ''。用假 fetch 捕获 URL，不起服务。
 */
import { consumeSse, request } from '@shared/api';
import { ErrorResponseSchema } from '@entities/agent-run';

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

await request('/agent/runs/run_2', { schema: ErrorResponseSchema, fetch: fakeFetch });
check(
  'request() without baseUrl falls back to env (same-origin default)',
  `${import.meta.env.VITE_API_BASE_URL ?? ''}/agent/runs/run_2`,
  seen.at(-1) ?? '',
);

await consumeSse(
  { path: '/agent/runs', body: {}, fetch: fakeSse, baseUrl: 'https://gw.example.com' },
  () => {},
);
check(
  'consumeSse() prefixes explicit baseUrl',
  'https://gw.example.com/agent/runs',
  seen.at(-1) ?? '',
);

// 空串必须被当作显式 baseUrl（同源），不能被 ?? 吞掉；而 undefined 才回落 env —— 两者语义不同
await consumeSse({ path: '/agent/runs', body: {}, fetch: fakeSse, baseUrl: '' }, () => {});
check("consumeSse() with baseUrl '' stays same-origin", '/agent/runs', seen.at(-1) ?? '');

process.stdout.write(`verify-transport: ${failed === 0 ? 'OK' : `${failed} failed`}\n`);
process.exit(failed > 0 ? 1 : 0);
