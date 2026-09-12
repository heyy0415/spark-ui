import { request } from './http';
import type { ActionRequest, IntentRequest, RunSummary } from './contracts';
import { ActionRequestSchema, IntentRequestSchema, RunSummarySchema } from './contracts';

/**
 * 宿主注入的传输参数：baseUrl（缺省 env.VITE_API_BASE_URL，同源为 ''；端点已带 /agent 前缀）与 fetch（缺省 window.fetch；
 * 宿主要带登录态就在这里包一层）。前端不再有身份概念——身份完全在宿主工程（change 5）。
 */
export interface Transport {
  baseUrl: string;
  fetch: typeof fetch;
}

/** GET /agent/runs/{runId}，经统一 request（HttpError + Zod）校验 run-summary 契约。 */
export async function getRun(runId: string, transport: Transport): Promise<RunSummary> {
  return request(`${AGENT_RUNS_PATH}/${encodeURIComponent(runId)}`, {
    schema: RunSummarySchema,
    baseUrl: transport.baseUrl,
    fetch: transport.fetch,
  });
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
