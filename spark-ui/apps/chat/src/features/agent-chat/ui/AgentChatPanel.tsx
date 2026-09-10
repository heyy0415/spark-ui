import { useCallback, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { UiAction } from '@spark-ui/core';
import {
  ActionBar,
  COMPONENT_TYPES,
  RunStatus,
  SchemaRenderer,
  SchemaSkeleton,
} from '@spark-ui/core';
import { HttpError } from '@shared/api';
import { env } from '@shared/config';
import { Button } from '@shared/ui';
import { FormIncompleteError, useAgentRun } from '../api/useAgentRun';
import type { ChatTurn } from '../model/runView';
import { skeletonVariant } from '../model/runView';
import styles from './AgentChatPanel.module.css';

/**
 * 宿主只需给 conversationId；baseUrl / fetch 可选注入（宿主要带登录态就传自己的 fetch）。前端始终只发自然语言，
 * 没有页面上下文、没有身份字段。会话只在内存里，刷新即空。
 */
export interface AgentChatPanelProps {
  conversationId: string;
  baseUrl?: string;
  fetch?: typeof fetch;
}

/** 空会话时的示例问题：点击 = 发送同一条文本（与行内指令同一路径）。 */
const EXAMPLE_CHIPS = [
  '看看我的订单',
  '有什么商品',
  '查看订单 10030 的物流',
  '订单 10002 申请售后',
];

export function AgentChatPanel({ conversationId, baseUrl, fetch: hostFetch }: AgentChatPanelProps) {
  const [input, setInput] = useState('');
  // 缺省 baseUrl 回落到 VITE_API_BASE_URL（同源为 ''），不能写成 ''：否则会短路 env（评审 M-1）；
  // 缺省 fetch 绑定到 globalThis（注入的 fetch 由宿主自行保证可直接调用）
  const transport = useMemo(
    () => ({
      baseUrl: baseUrl ?? env.VITE_API_BASE_URL,
      fetch: hostFetch ?? globalThis.fetch.bind(globalThis),
    }),
    [baseUrl, hostFetch],
  );
  const { view, start, submitAction, onFormChange, busy } = useAgentRun({
    conversationId,
    transport,
  });
  const { mutate } = start;
  // 消息流是否跟随到底（用户手动上翻超过一屏后停止跟随，新回合恢复）
  const stickRef = useRef(true);

  /** 发送一条用户消息：输入框、示例 chip、行内指令都走这里，文本原样提交、不拼接不改写。 */
  const send = useCallback(
    (message: string) => {
      if (!message || busy) {
        return;
      }
      stickRef.current = true; // 新回合：无论之前翻到哪，都回到底部
      mutate({
        message,
        clientCapabilities: { uiSchemaVersion: '1.0', components: [...COMPONENT_TYPES] },
      });
    },
    [busy, mutate],
  );

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const message = input.trim();
    if (!message) {
      return;
    }
    setInput('');
    send(message);
  };

  const onAction = (a: UiAction) => submitAction.mutate(a);
  const err = start.error ?? submitAction.error;
  const errText =
    err instanceof FormIncompleteError
      ? `请先填写：${err.missing.join('、')}`
      : err instanceof HttpError
        ? `请求失败（${err.status}）`
        : err
          ? '请求失败'
          : null;

  // 消息流高度一变就滚到底（ResizeObserver 盯内容容器，比按事件猜时机可靠：表格 / 骨架的渲染时刻不确定）。
  // 用户手动往上翻超过一屏则不再跟随；新回合开始时恢复跟随。
  const bottomRef = useRef<HTMLDivElement>(null);
  const streamRef = useRef<HTMLDivElement>(null);
  const turns = view.turns;
  const lastIdx = turns.length - 1;
  const onStreamScroll = () => {
    const el = streamRef.current;
    if (el) {
      stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < el.clientHeight;
    }
  };
  // ref 回调：列表挂载时开始观察，卸载时断开；不经 effect 依赖
  const observeList = useCallback((list: HTMLOListElement | null) => {
    if (!list) {
      return;
    }
    const ro = new ResizeObserver(() => {
      if (stickRef.current) {
        bottomRef.current?.scrollIntoView({ block: 'end' });
      }
    });
    ro.observe(list);
    return () => ro.disconnect();
  }, []);

  return (
    <div className={styles['panel']}>
      <div className={styles['stream']} ref={streamRef} onScroll={onStreamScroll}>
        {turns.length === 0 ? (
          <div className={styles['empty']}>
            <p className={styles['emptyHint']}>直接说你想做什么，比如：</p>
            <div className={styles['chips']} role="group" aria-label="示例问题">
              {EXAMPLE_CHIPS.map((c) => (
                <button
                  key={c}
                  type="button"
                  className={styles['chip']}
                  data-chip={c}
                  disabled={busy}
                  onClick={() => send(c)}
                >
                  {c}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <ol className={styles['turns']} aria-label="对话消息" ref={observeList}>
            {turns.map((t, i) => (
              <TurnView
                key={t.id}
                turn={t}
                isLast={i === lastIdx}
                busy={busy}
                onIntent={send}
                onFormChange={onFormChange}
                onAction={onAction}
              />
            ))}
          </ol>
        )}
        <div ref={bottomRef} />
      </div>

      <form className={styles['inputBar']} onSubmit={onSubmit}>
        {errText ? (
          <p role="alert" className={styles['error']}>
            {errText}
          </p>
        ) : null}
        <div className={styles['inputRow']}>
          <label htmlFor="agent-input" className={styles['srOnly']}>
            输入你的需求
          </label>
          <input
            id="agent-input"
            className={styles['input']}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="例如：帮我把订单 10001 退款"
            disabled={busy}
            maxLength={2000}
            autoComplete="off"
          />
          <Button type="submit" disabled={busy || input.trim().length === 0}>
            发送
          </Button>
        </div>
      </form>
    </div>
  );
}

interface TurnViewProps {
  turn: ChatTurn;
  isLast: boolean;
  busy: boolean;
  onIntent: (intent: string) => void;
  onFormChange: (values: Record<string, string | number | boolean>) => void;
  onAction: (a: UiAction) => void;
}

/** 一个回合 = 右侧用户气泡 + 左侧助手气泡（状态条 → 文本 → 骨架 / 屏 → 确认按钮）。历史回合只读。 */
function TurnView({ turn, isLast, busy, onIntent, onFormChange, onAction }: TurnViewProps) {
  const loading = turn.status === 'streaming' && turn.ui === null;
  const canConfirm = isLast && turn.status === 'waiting_confirmation' && turn.ui !== null;
  return (
    <>
      <li className={styles['turn']} data-role="user">
        <div className={styles['bubbleUser']}>{turn.user}</div>
      </li>
      <li className={styles['turn']} data-role="assistant" data-status={turn.status}>
        <div className={styles['bubbleAssistant']}>
          <RunStatus
            status={turn.status}
            tools={turn.tools.map((t) => ({ displayName: t.displayName, status: t.status }))}
            {...(turn.failure ? { text: turn.failure.message } : {})}
          />
          {turn.texts.map((text, i) => (
            <p key={i} className={styles['assistantText']}>
              {text}
            </p>
          ))}
          {loading ? <SchemaSkeleton variant={skeletonVariant(turn)} /> : null}
          {turn.ui ? (
            <section className={styles['ui']} aria-live={isLast ? 'polite' : 'off'}>
              <SchemaRenderer
                ui={turn.ui}
                onIntent={onIntent}
                readOnly={!isLast}
                {...(isLast ? { onFormChange } : {})}
              />
              {canConfirm ? (
                <ActionBar actions={turn.ui.actions} disabled={busy} onAction={onAction} />
              ) : null}
            </section>
          ) : null}
        </div>
      </li>
    </>
  );
}
