import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useCallback, useRef } from 'react';
import type { FormValues, UiAction, UiSchema } from '@spark-ui/core';
import { FormPropsSchema } from '@spark-ui/core';
import type { IntentRequest, Transport } from '@entities/agent-run';
import {
  AGENT_RUNS_PATH,
  actionPath,
  buildActionRequest,
  buildIntentRequest,
  SseEventSchema,
} from '@entities/agent-run';
import { consumeSse } from '@shared/api';
import type { AgentRunView } from '../model/runView';
import { beginTurn, emptyView, reduceEvent } from '../model/runView';

/** 服务端状态走 TanStack Query（coding-standard §4）；SSE 事件逐帧归约后写入 cache。 */
const viewKey = (conversationId: string) => ['agent-run', conversationId] as const;

/** 本地校验失败（未发请求）：不会消耗一次性 confirmationToken。 */
export class FormIncompleteError extends Error {
  override readonly name = 'FormIncompleteError';
  readonly missing: string[];
  constructor(missing: string[]) {
    super(`required fields missing: ${missing.join(', ')}`);
    this.missing = missing;
  }
}

/** 按当前屏 Form.props.fields[].required 找出未填写的字段 label。 */
export function missingRequiredFields(ui: UiSchema | null, values: FormValues): string[] {
  if (!ui) {
    return [];
  }
  const missing: string[] = [];
  for (const c of ui.components) {
    if (c.type !== 'Form') {
      continue;
    }
    const parsed = FormPropsSchema.safeParse(c.props);
    if (!parsed.success) {
      continue;
    }
    for (const f of parsed.data.fields) {
      const v = values[f.name];
      if (f.required && (v === undefined || v === '')) {
        missing.push(f.label);
      }
    }
  }
  return missing;
}

/** 流结束但没有终态事件（连接中断 / 服务端异常退出）→ 显式失败，避免 UI 永久停留在 streaming。 */
function failIfStillStreaming(
  view: AgentRunView | undefined,
  conversationId: string,
): AgentRunView {
  const v = view ?? emptyView(conversationId);
  if (v.phase !== 'streaming') {
    return v;
  }
  return {
    ...v,
    phase: 'failed',
    pendingActionId: null,
    failure: { code: 'INTERNAL_ERROR', message: '连接中断，请重试' },
  };
}

export interface UseAgentRunOptions {
  conversationId: string;
  transport: Transport;
}

export function useAgentRun({ conversationId, transport }: UseAgentRunOptions) {
  const qc = useQueryClient();
  const abortRef = useRef<AbortController | null>(null);
  const formRef = useRef<FormValues>({});

  const view = useQuery<AgentRunView>({
    queryKey: viewKey(conversationId),
    queryFn: () => emptyView(conversationId),
    initialData: () => emptyView(conversationId),
    staleTime: Infinity,
  });

  const apply = useCallback(
    (frame: { event: string; data: unknown }) => {
      const parsed = SseEventSchema.safeParse(frame);
      if (!parsed.success) {
        console.error(
          '[agent-run] SSE frame rejected by contract',
          frame.event,
          parsed.error.issues,
        );
        return;
      }
      qc.setQueryData<AgentRunView>(viewKey(conversationId), (prev) =>
        reduceEvent(prev ?? emptyView(conversationId), parsed.data),
      );
    },
    [qc, conversationId],
  );

  const stream = useCallback(
    async (path: string, body: unknown) => {
      abortRef.current?.abort();
      const ac = new AbortController();
      abortRef.current = ac;
      await consumeSse(
        {
          path,
          body,
          signal: ac.signal,
          fetch: transport.fetch,
          baseUrl: transport.baseUrl,
        },
        apply,
      );
      if (!ac.signal.aborted) {
        qc.setQueryData<AgentRunView>(viewKey(conversationId), (prev) =>
          failIfStillStreaming(prev, conversationId),
        );
      }
    },
    [apply, transport, qc, conversationId],
  );

  const start = useMutation({
    mutationFn: async (intent: Omit<IntentRequest, 'conversationId'>) => {
      formRef.current = {};
      qc.setQueryData<AgentRunView>(viewKey(conversationId), (prev) =>
        beginTurn(prev ?? emptyView(conversationId), intent.message),
      );
      await stream(AGENT_RUNS_PATH, buildIntentRequest({ ...intent, conversationId }));
    },
  });

  const submitAction = useMutation({
    mutationFn: async (action: UiAction) => {
      const current = qc.getQueryData<AgentRunView>(viewKey(conversationId));
      if (!current?.runId) {
        throw new Error('no active run');
      }
      if (action.type === 'cancel') {
        qc.setQueryData(viewKey(conversationId), {
          ...current,
          phase: 'completed',
          pendingActionId: null,
        });
        return;
      }
      if (!action.confirmationToken) {
        throw new Error('action has no confirmationToken');
      }
      // 提交前本地校验必填项：缺失则不发请求，避免白白消耗一次性令牌
      const missing = missingRequiredFields(current.ui, formRef.current);
      if (missing.length > 0) {
        throw new FormIncompleteError(missing);
      }
      const body = buildActionRequest({
        confirmationToken: action.confirmationToken,
        formData: formRef.current,
      });
      qc.setQueryData(viewKey(conversationId), {
        ...current,
        phase: 'streaming',
        pendingActionId: null,
      });
      await stream(actionPath(current.runId, action.id), body);
    },
  });

  const onFormChange = useCallback((values: FormValues) => {
    formRef.current = values;
  }, []);

  return {
    view: view.data,
    start,
    submitAction,
    onFormChange,
    busy: start.isPending || submitAction.isPending,
  };
}
