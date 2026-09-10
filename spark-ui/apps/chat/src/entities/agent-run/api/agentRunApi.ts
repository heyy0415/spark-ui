import type { ActionRequest, IntentRequest, RunSummary } from '../model/types';
import { ActionRequestSchema, IntentRequestSchema, RunSummarySchema } from '../model/types';

/**
 * 宿主注入的传输参数：baseUrl（默认同源 ''，端点已带 /agent 前缀）与 fetch（默认 window.fetch；宿主要带登录态就在这里包一层）。
 * 前端不再有身份概念——身份完全在宿主工程（change 5）。
 */
export interface Transport {
  baseUrl: string;
  fetch: typeof fetch;
}

/** GET /agent/runs/{runId}，响应经 run-summary 契约校验。 */
export async function getRun(runId: string, transport: Transport): Promise<RunSummary> {
  const res = await transport.fetch(
    `${transport.baseUrl}${AGENT_RUNS_PATH}/${encodeURIComponent(runId)}`,
    { headers: { Accept: 'application/json' } },
  );
  if (!res.ok) {
    throw new Error(`GET run failed: ${res.status}`);
  }
  return RunSummarySchema.parse(await res.json());
}

/** 构造并校验发起请求体（SSE 传输由 shared/api/sseClient 承载）。 */
export function buildIntentRequest(input: IntentRequest): IntentRequest {
  return IntentRequestSchema.parse(input);
}

export function buildActionRequest(input: ActionRequest): ActionRequest {
  return ActionRequestSchema.parse(input);
}

export const AGENT_RUNS_PATH = '/agent/runs';

export function actionPath(runId: string, actionId: string): string {
  return `${AGENT_RUNS_PATH}/${encodeURIComponent(runId)}/actions/${encodeURIComponent(actionId)}`;
}
