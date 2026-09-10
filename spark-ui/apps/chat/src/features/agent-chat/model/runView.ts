import type { UiSchema } from '@spark-ui/core';
import type { RunFailureCode, SseEvent } from '@entities/agent-run';

/**
 * agent-chat 的客户端状态：由 SSE 事件流归约而来，存放在 TanStack Query cache（key ['agent-run', conversationId]），
 * 只在内存里、不持久化。按「回合」组织：一条用户消息 + 该次 Run 的全部可见产物（进度 / 文本 / 屏 / 终态）。
 * 不保存计划、参数原文。
 */
export interface ToolProgress {
  toolCallId: string;
  toolId: string;
  displayName: string;
  status: 'selected' | 'running' | 'succeeded' | 'failed';
  durationMs?: number;
  summary?: string;
}

export type TurnStatus = 'streaming' | 'waiting_confirmation' | 'completed' | 'failed';

export interface ChatTurn {
  /** 客户端生成的回合 id（React key）。 */
  id: string;
  runId: string | null;
  /** 用户原话：输入框 / chip / 行内指令都原样进这里。 */
  user: string;
  status: TurnStatus;
  tools: ToolProgress[];
  /** message.delta 文本，按到达顺序。 */
  texts: string[];
  /** 该回合当前屏（ui.replace 覆盖、ui.patch 合并）。 */
  ui: UiSchema | null;
  pendingActionId: string | null;
  failure: { code: RunFailureCode; message: string } | null;
}

export interface AgentRunView {
  conversationId: string;
  turns: ChatTurn[];
}

export function emptyView(conversationId: string): AgentRunView {
  return { conversationId, turns: [] };
}

/** 当前（最后一个）回合；没有回合时 null。 */
export function lastTurn(view: AgentRunView): ChatTurn | null {
  return view.turns.length > 0 ? (view.turns[view.turns.length - 1] ?? null) : null;
}

let seq = 0;

/** 用户发送一条消息：追加新回合。历史回合原样保留（只读展示），不再撤屏——聊天流里每轮回答都留着。 */
export function beginTurn(view: AgentRunView, text: string): AgentRunView {
  seq += 1;
  const turn: ChatTurn = {
    id: `t${Date.now().toString(36)}_${seq}`,
    runId: null,
    user: text,
    status: 'streaming',
    tools: [],
    texts: [],
    ui: null,
    pendingActionId: null,
    failure: null,
  };
  return { ...view, turns: [...view.turns, turn] };
}

/** 用户点了确认：同一回合继续（不新开回合），状态回到 streaming，等结果屏替换当前屏。 */
export function beginConfirm(view: AgentRunView): AgentRunView {
  return updateLast(view, (t) => ({ ...t, status: 'streaming', pendingActionId: null }));
}

/** 用户取消确认：回合直接完成，屏保留但 ActionBar 不再渲染。 */
export function cancelConfirm(view: AgentRunView): AgentRunView {
  return updateLast(view, (t) => ({ ...t, status: 'completed', pendingActionId: null }));
}

/** 流结束但没有终态事件（连接中断 / 服务端异常退出）→ 显式失败，避免永久 loading。 */
export function failIfStillStreaming(view: AgentRunView): AgentRunView {
  return updateLast(view, (t) =>
    t.status === 'streaming'
      ? {
          ...t,
          status: 'failed',
          pendingActionId: null,
          failure: { code: 'INTERNAL_ERROR', message: '连接中断，请重试' },
        }
      : t,
  );
}

function updateLast(view: AgentRunView, f: (t: ChatTurn) => ChatTurn): AgentRunView {
  const last = lastTurn(view);
  if (!last) {
    return view;
  }
  return { ...view, turns: [...view.turns.slice(0, -1), f(last)] };
}

/** 纯归约：一帧事件 → 新视图。事件只作用于最后一个回合。 */
export function reduceEvent(view: AgentRunView, ev: SseEvent): AgentRunView {
  return updateLast(view, (t) => reduceTurn(t, ev));
}

function reduceTurn(t: ChatTurn, ev: SseEvent): ChatTurn {
  switch (ev.event) {
    case 'run.started':
      return { ...t, runId: ev.data.runId, status: 'streaming', failure: null };
    case 'message.delta':
      return { ...t, texts: [...t.texts, ev.data.text] };
    case 'tool.selected':
      return {
        ...t,
        tools: [
          ...t.tools,
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
        ...t,
        tools: t.tools.map((x) =>
          x.toolCallId === ev.data.toolCallId ? { ...x, status: 'running' } : x,
        ),
      };
    case 'tool.completed':
      return {
        ...t,
        tools: t.tools.map((x) =>
          x.toolCallId === ev.data.toolCallId
            ? {
                ...x,
                status: ev.data.status,
                durationMs: ev.data.durationMs,
                ...(ev.data.summary === undefined ? {} : { summary: ev.data.summary }),
              }
            : x,
        ),
      };
    case 'ui.replace':
      return { ...t, ui: ev.data.ui };
    case 'ui.patch': {
      if (!t.ui || t.ui.screenId !== ev.data.screenId) {
        return t;
      }
      const byId = new Map(ev.data.components.map((c) => [c.id, c]));
      return { ...t, ui: { ...t.ui, components: t.ui.components.map((c) => byId.get(c.id) ?? c) } };
    }
    case 'confirmation.required':
      return { ...t, status: 'waiting_confirmation', pendingActionId: ev.data.actionId };
    case 'run.completed':
      return { ...t, status: 'completed', pendingActionId: null };
    case 'run.failed':
      return {
        ...t,
        status: 'failed',
        pendingActionId: null,
        failure: { code: ev.data.code, message: ev.data.message },
      };
    default:
      return t;
  }
}

/** 骨架形状：按该回合最后一个工具 id 粗猜（猜错只影响形状）。 */
export function skeletonVariant(t: ChatTurn): 'table' | 'card' | 'form' | 'generic' {
  const last = t.tools[t.tools.length - 1]?.toolId ?? '';
  if (/\.list\./.test(last)) {
    return 'table';
  }
  if (/\.(detail|eligibility|status)\.|\.preview$|\.logistics\./.test(last)) {
    return 'card';
  }
  return 'generic';
}
