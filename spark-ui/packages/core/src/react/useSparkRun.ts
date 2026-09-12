import { useCallback, useMemo, useRef, useState, useSyncExternalStore } from 'react';
// 直接指向 schema 而非 '../index'：渲染层入口会拉进 antd / antd-mobile，
// 而本文件只需要纯 Zod 的契约类型。headless 绑定不该依赖渲染层。
import type { UiAction, UiSchema } from '../schema/uiSchema';
import type { FormValues } from '../registry/types';
import { FormPropsSchema } from '../schema/uiSchema';
import type { IntentRequest, Transport } from '../client';
import {
  AGENT_RUNS_PATH,
  actionPath,
  beginConfirm,
  beginTurn,
  buildActionRequest,
  buildIntentRequest,
  cancelConfirm,
  consumeSse,
  createRunStore,
  failIfStillStreaming,
  lastTurn,
  reduceEvent,
  SseEventSchema,
} from '../client';

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

/**
 * 一次异步动作的状态，形状对齐原先 TanStack Query 的 mutation：
 * `mutate` 触发、`error` 供展示、`isPending` 供禁用按钮。
 *
 * <p>只保留被实际消费的三个字段——原来用 `useMutation` 也只用到这些。
 */
export interface RunAction<T> {
  mutate: (input: T) => void;
  error: Error | null;
  isPending: boolean;
}

export interface UseSparkRunOptions {
  conversationId: string;
  transport: Transport;
}

/**
 * 聊天运行时的 React 绑定：订阅 SSE、归约事件、提交确认动作。
 *
 * <p>状态放在 `client/runStore`（零框架依赖），这里只用 `useSyncExternalStore` 接上——
 * 它是 React 18+ 为外部 store 提供的官方接口，快照引用相等即不重渲染，无 tearing。
 */
export function useSparkRun({ conversationId, transport }: UseSparkRunOptions) {
  // conversationId 变化时重建 store：不同会话不该共享视图
  const store = useMemo(() => createRunStore(conversationId), [conversationId]);
  const view = useSyncExternalStore(store.subscribe, store.getSnapshot);

  const abortRef = useRef<AbortController | null>(null);
  const formRef = useRef<FormValues>({});
  const [startState, setStartState] = useState<{ error: Error | null; pending: boolean }>({
    error: null,
    pending: false,
  });
  const [actionState, setActionState] = useState<{ error: Error | null; pending: boolean }>({
    error: null,
    pending: false,
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
      store.dispatch((prev) => reduceEvent(prev, parsed.data));
    },
    [store],
  );

  /** 流收口：无论正常结束、HTTP 非 2xx 还是网络异常，回合不能停在 streaming。 */
  const finalize = useCallback(() => {
    store.dispatch((prev) => failIfStillStreaming(prev));
  }, [store]);

  const stream = useCallback(
    async (path: string, body: unknown) => {
      abortRef.current?.abort();
      const ac = new AbortController();
      abortRef.current = ac;
      const req = {
        path,
        body,
        signal: ac.signal,
        fetch: transport.fetch,
        baseUrl: transport.baseUrl,
      };
      // 不用 try/finally：让 rejection 照常抛给调用方（error 展示），收口在 .finally 里做
      await consumeSse(req, apply).finally(() => {
        if (!ac.signal.aborted) {
          finalize();
        }
      });
    },
    [apply, transport, finalize],
  );

  const startMutate = useCallback(
    (intent: Omit<IntentRequest, 'conversationId'>) => {
      formRef.current = {};
      setStartState({ error: null, pending: true });
      store.dispatch((prev) => beginTurn(prev, intent.message));
      stream(AGENT_RUNS_PATH, buildIntentRequest({ ...intent, conversationId })).then(
        () => setStartState({ error: null, pending: false }),
        (e: unknown) =>
          setStartState({ error: e instanceof Error ? e : new Error(String(e)), pending: false }),
      );
    },
    [store, stream, conversationId],
  );

  const actionMutate = useCallback(
    (action: UiAction) => {
      const current = store.getSnapshot();
      const turn = lastTurn(current);
      const fail = (e: Error) => setActionState({ error: e, pending: false });

      if (!turn?.runId) {
        fail(new Error('no active run'));
        return;
      }
      if (action.type === 'cancel') {
        setActionState({ error: null, pending: false });
        store.dispatch(cancelConfirm(current));
        return;
      }
      if (!action.confirmationToken) {
        fail(new Error('action has no confirmationToken'));
        return;
      }
      // 提交前本地校验必填项：缺失则不发请求，避免白白消耗一次性令牌
      const missing = missingRequiredFields(turn.ui, formRef.current);
      if (missing.length > 0) {
        fail(new FormIncompleteError(missing));
        return;
      }
      const body = buildActionRequest({
        confirmationToken: action.confirmationToken,
        formData: formRef.current,
      });
      setActionState({ error: null, pending: true });
      store.dispatch(beginConfirm(current));
      stream(actionPath(turn.runId, action.id), body).then(
        () => setActionState({ error: null, pending: false }),
        (e: unknown) =>
          setActionState({ error: e instanceof Error ? e : new Error(String(e)), pending: false }),
      );
    },
    [store, stream],
  );

  const onFormChange = useCallback((values: FormValues) => {
    formRef.current = values;
  }, []);

  const start: RunAction<Omit<IntentRequest, 'conversationId'>> = {
    mutate: startMutate,
    error: startState.error,
    isPending: startState.pending,
  };
  const submitAction: RunAction<UiAction> = {
    mutate: actionMutate,
    error: actionState.error,
    isPending: actionState.pending,
  };

  return {
    view,
    start,
    submitAction,
    onFormChange,
    busy: startState.pending || actionState.pending,
  };
}
