import { z } from 'zod';
import type { UiSchema } from '@spark-ui/core';
import type { RunFailureCode, SseEvent } from '@entities/agent-run';

/**
 * agent-chat 的客户端状态：由 SSE 事件流归约而来，存放在 TanStack Query cache（key ['agent-run', runId]）。
 * 只保存用户可见的信息：进度条目、当前 UI、等待确认的 actionId、终态。不保存计划、参数原文。
 */
export interface ToolProgress {
  toolCallId: string;
  toolId: string;
  displayName: string;
  status: 'selected' | 'running' | 'succeeded' | 'failed';
  durationMs?: number;
  summary?: string;
}

export type RunPhase = 'idle' | 'streaming' | 'waiting_confirmation' | 'completed' | 'failed';

/** 消息区条目：用户发出的文本与助手的提示分开展示。 */
export interface ChatMessage {
  role: 'user' | 'assistant';
  text: string;
}

export interface AgentRunView {
  runId: string | null;
  conversationId: string;
  phase: RunPhase;
  messages: ChatMessage[];
  tools: ToolProgress[];
  ui: UiSchema | null;
  pendingActionId: string | null;
  failure: { code: RunFailureCode; message: string } | null;
}

export function emptyView(conversationId: string): AgentRunView {
  return {
    runId: null,
    conversationId,
    phase: 'idle',
    messages: [],
    tools: [],
    ui: null,
    pendingActionId: null,
    failure: null,
  };
}

/** 用户发送一条消息（输入框或行内指令）：记入消息区，进度清空，上一屏保留到新屏 ui.replace 到达。 */
export function beginTurn(view: AgentRunView, text: string): AgentRunView {
  // 上一屏若含确认动作（等待确认、被取消、确认后失败都可能残留），新一轮开始时撤掉：旧令牌已失效，留着可填的 Form 会误导用户
  const hadConfirmation = view.ui?.actions.some((a) => a.type === 'submit') ?? false;
  const ui = view.phase === 'waiting_confirmation' || hadConfirmation ? null : view.ui;
  return {
    ...view,
    ui,
    phase: 'streaming',
    messages: [...view.messages, { role: 'user', text }],
    tools: [],
    pendingActionId: null,
    failure: null,
  };
}

/** 纯归约：一帧事件 → 新视图。ui.patch 按组件 id 合并覆盖。 */
export function reduceEvent(view: AgentRunView, ev: SseEvent): AgentRunView {
  switch (ev.event) {
    case 'run.started':
      // 连续对话：保留消息记录与上一屏，只换 runId
      return { ...view, runId: ev.data.runId, phase: 'streaming', tools: [], failure: null };
    case 'message.delta':
      return { ...view, messages: [...view.messages, { role: 'assistant', text: ev.data.text }] };
    case 'tool.selected':
      return {
        ...view,
        tools: [
          ...view.tools,
          {
            toolCallId: ev.data.toolCallId,
            toolId: ev.data.toolId,
            displayName: ev.data.displayName,
            status: 'selected',
          },
        ],
      };
    case 'tool.started':
      return {
        ...view,
        tools: view.tools.map((t) =>
          t.toolCallId === ev.data.toolCallId ? { ...t, status: 'running' } : t,
        ),
      };
    case 'tool.completed':
      return {
        ...view,
        tools: view.tools.map((t) =>
          t.toolCallId === ev.data.toolCallId
            ? {
                ...t,
                status: ev.data.status,
                durationMs: ev.data.durationMs,
                ...(ev.data.summary === undefined ? {} : { summary: ev.data.summary }),
              }
            : t,
        ),
      };
    case 'ui.replace':
      return { ...view, ui: ev.data.ui };
    case 'ui.patch': {
      if (!view.ui || view.ui.screenId !== ev.data.screenId) {
        return view;
      }
      const byId = new Map(ev.data.components.map((c) => [c.id, c]));
      return {
        ...view,
        ui: { ...view.ui, components: view.ui.components.map((c) => byId.get(c.id) ?? c) },
      };
    }
    case 'confirmation.required':
      return { ...view, phase: 'waiting_confirmation', pendingActionId: ev.data.actionId };
    case 'run.completed':
      return { ...view, phase: 'completed', pendingActionId: null };
    case 'run.failed':
      return {
        ...view,
        phase: 'failed',
        pendingActionId: null,
        failure: { code: ev.data.code, message: ev.data.message },
      };
    default:
      return view;
  }
}

/** URL query → pageContext（不可信输入，仅作为提示传给后端；后端会重新鉴权）。 */
export const PageContextQuerySchema = z.object({
  page: z
    .string()
    .min(1)
    .max(64)
    .regex(/^[a-z0-9-]+$/)
    .default('agent'),
  entityType: z
    .string()
    .min(1)
    .max(32)
    .regex(/^[a-z]+$/)
    .optional(),
  entityId: z.string().min(1).max(64).optional(),
});
export type PageContextQuery = z.infer<typeof PageContextQuerySchema>;
