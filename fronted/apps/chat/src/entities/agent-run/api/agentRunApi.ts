import { request } from '@shared/api';
import type { ActionRequest, IntentRequest, RunSummary } from '../model/types';
import { ActionRequestSchema, IntentRequestSchema, RunSummarySchema } from '../model/types';

/** 首期身份头（真实 IdP 为后续 change）。 */
export interface Principal {
  userId: string;
  tenantId: string;
}

export function principalHeaders(p: Principal): Record<string, string> {
  return { 'X-Tenant-Id': p.tenantId, 'X-User-Id': p.userId };
}

/** GET /agent/runs/{runId}，响应经 run-summary 契约校验。 */
export async function getRun(runId: string, principal: Principal): Promise<RunSummary> {
  return request(`/agent/runs/${encodeURIComponent(runId)}`, {
    schema: RunSummarySchema,
    headers: principalHeaders(principal),
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
