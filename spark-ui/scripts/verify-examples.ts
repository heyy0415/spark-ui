/**
 * pnpm -C spark-ui run verify-examples（= vite-node -c apps/chat/vite.config.ts scripts/verify-examples.ts，serve 模式，@spark-ui/core 解析到 core src）
 *
 * 用前端 Zod 投影逐个校验契约示例（.harness/contracts/examples/*.example.json），
 * 保证 ui-schema 投影（@spark-ui/core）与其余投影（apps/chat entities）与契约真源一致（contracts.md §1）。
 * 前端只投影 6 个契约：intent / action / ui-schema / run-summary / sse-events / error 的全部示例（数量随 .harness/contracts/examples 变化）。
 * 同时校验 examples/invalid/*.invalid.json 必须被 Zod 拒绝（投影比契约宽会在此暴露）。输出 "N examples OK"；任一失败退出码 1。以 vite-node 运行（路径别名由 vite.config 解析），不在 typecheck 范围。
 */
import { readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { UiSchemaSchema } from '@spark-ui/core';
import type { ZodType } from 'zod';
import {
  ActionRequestSchema,
  ErrorResponseSchema,
  IntentRequestSchema,
  RunSummarySchema,
  SseEventSchema,
} from '@entities/agent-run';

const examplesDir = join(process.cwd(), '..', '.harness', 'contracts', 'examples'); // cwd = spark-ui

const schemaFor: Record<string, ZodType> = {
  'intent-request': IntentRequestSchema,
  'action-request': ActionRequestSchema,
  'ui-schema': UiSchemaSchema,
  'run-summary': RunSummarySchema,
  'sse-events': SseEventSchema,
  'error-response': ErrorResponseSchema,
};

function contractOf(file: string): string | null {
  const stem = file.replace(/\.example\.json$/, '');
  let best: string | null = null;
  for (const name of Object.keys(schemaFor)) {
    if (
      (stem === name || stem.startsWith(`${name}.`)) &&
      (best === null || name.length > best.length)
    ) {
      best = name;
    }
  }
  return best;
}

let ok = 0;
let failed = 0;
for (const file of readdirSync(examplesDir)
  .filter((f) => f.endsWith('.example.json'))
  .toSorted()) {
  const contract = contractOf(file);
  if (contract === null) {
    continue; // tool-* 契约不由前端投影
  }
  const data: unknown = JSON.parse(readFileSync(join(examplesDir, file), 'utf-8'));
  const schema = schemaFor[contract];
  if (!schema) {
    continue;
  }
  const r = schema.safeParse(data);
  if (r.success) {
    ok += 1;
  } else {
    failed += 1;
    process.stderr.write(`✗ ${file}: ${JSON.stringify(r.error.issues.slice(0, 3))}\n`);
  }
}
// 反例：前端投影必须与契约一样拒绝（投影比契约宽会在此暴露）
let rejected = 0;
const invalidDir = join(examplesDir, 'invalid');
for (const file of readdirSync(invalidDir)
  .filter((f) => f.endsWith('.invalid.json'))
  .toSorted()) {
  const contract = file.split('.')[0] ?? '';
  const schema = schemaFor[contract];
  if (!schema) {
    continue; // tool-* 契约不由前端投影
  }
  const data: unknown = JSON.parse(readFileSync(join(invalidDir, file), 'utf-8'));
  if (schema.safeParse(data).success) {
    failed += 1;
    process.stderr.write(
      `✗ invalid example ${file} was ACCEPTED by the Zod projection (looser than contract)\n`,
    );
  } else {
    rejected += 1;
  }
}
process.stdout.write(
  `${ok} examples OK, ${rejected} invalid rejected${failed > 0 ? `, ${failed} failed` : ''}\n`,
);
process.exit(failed > 0 ? 1 : 0);
