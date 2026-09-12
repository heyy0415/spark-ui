import { memo, useCallback, useEffect, useMemo, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import type { UiAction } from '@spark-ui/core';
import {
  ActionBar,
  COMPONENT_TYPES,
  RunStatus,
  SchemaRenderer,
  SchemaSkeleton,
} from '@spark-ui/core';
import { HttpError } from '@spark-ui/core/client';
import { env } from '@shared/config';
import { Button } from '@shared/ui';
import { FormIncompleteError, useSparkRun } from '@spark-ui/core/react';
import type { ChatTurn } from '@spark-ui/core/client';
import { skeletonVariant } from '@spark-ui/core/client';
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
  const { view, start, submitAction, onFormChange, busy } = useSparkRun({
    conversationId,
    transport,
  });
  const { mutate } = start;
  // 消息流是否跟随（用户手动上翻超过半屏后停止，新回合恢复）
  const stickRef = useRef(true);
  // busy 走 ref：send 不因 busy 变化换引用，历史回合的 TurnView 才能 memo 住（effect 里同步，不在渲染期写 ref）
  const busyRef = useRef(busy);
  useEffect(() => {
    busyRef.current = busy;
  }, [busy]);

  /** 发送一条用户消息：输入框、示例 chip、行内指令都走这里，文本原样提交、不拼接不改写。 */
  const send = useCallback(
    (message: string) => {
      if (!message || busyRef.current) {
        return;
      }
      stickRef.current = true; // 新回合：无论之前翻到哪，都回到跟随
      mutate({
        message,
        clientCapabilities: { uiSchemaVersion: '1.0', components: [...COMPONENT_TYPES] },
      });
    },
    [mutate],
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

  // 消息流跟随：内容高度变化（ResizeObserver）时把「最后一个回合的用户气泡」滚到可视区顶部——新屏比视口高时用户先看到
  // 问题与表头，而不是表格底部；只滚 .stream 自身，不用 scrollIntoView（会把嵌入的宿主页面一起卷走）。
  // 用户往上翻超过半屏后停止跟随，新回合恢复。
  const streamRef = useRef<HTMLDivElement>(null);
  const turns = view.turns;
  const lastIdx = turns.length - 1;
  const onStreamScroll = () => {
    const el = streamRef.current;
    if (el) {
      stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < el.clientHeight / 2;
    }
  };
  const follow = useCallback(() => {
    const el = streamRef.current;
    if (!el || !stickRef.current) {
      return;
    }
    // user / assistant 两种 li 交替出现，:last-of-type 会选到 assistant；取全部 user li 的最后一个
    const users = el.querySelectorAll<HTMLElement>('li[data-role="user"]');
    const lastUser = users[users.length - 1];
    const target = lastUser ? lastUser.offsetTop - el.offsetTop - 8 : el.scrollHeight;
    // 内容不足一屏时贴底即可；否则把最后一轮的问题放到顶部
    el.scrollTop = Math.min(target, el.scrollHeight - el.clientHeight);
  }, []);
  // ref 回调：列表挂载时开始观察，卸载时断开；不经 effect 依赖
  const observeList = useCallback(
    (list: HTMLOListElement | null) => {
      if (!list) {
        return;
      }
      const ro = new ResizeObserver(follow);
      ro.observe(list);
      return () => ro.disconnect();
    },
    [follow],
  );

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

/** 一个回合 = 右侧用户气泡 + 左侧助手气泡（状态条 → 文本 → 骨架 / 屏 → 确认按钮）。历史回合只读；memo 住，SSE 帧只重渲最后回合。 */
const TurnView = memo(function TurnView({
  turn,
  isLast,
  busy,
  onIntent,
  onFormChange,
  onAction,
}: TurnViewProps) {
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
});
