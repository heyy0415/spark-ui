import { useCallback, useState } from 'react';
import type { FormEvent } from 'react';
import type { UiAction } from '@spark-ui/core';
import { ActionBar, COMPONENT_TYPES, SchemaRenderer } from '@spark-ui/core';
import type { Principal } from '@entities/agent-run';
import { HttpError } from '@shared/api';
import { Button } from '@shared/ui';
import { FormIncompleteError, useAgentRun } from '../api/useAgentRun';
import type { PageContextQuery } from '../model/runView';
import styles from './AgentChatPanel.module.css';

export interface AgentChatPanelProps {
  conversationId: string;
  principal: Principal;
  pageContext: PageContextQuery;
}

/** 演示页示例问题：点击 = 发送同一条文本（与行内指令同一路径）。 */
const EXAMPLE_CHIPS = [
  '看看我的订单',
  '有什么商品',
  '查看订单 10030 的物流',
  '订单 10002 申请售后',
];

export function AgentChatPanel({ conversationId, principal, pageContext }: AgentChatPanelProps) {
  const [input, setInput] = useState('');
  const { view, start, submitAction, onFormChange, busy } = useAgentRun({
    conversationId,
    principal,
  });
  // useMutation 返回对象每次渲染都是新引用；只依赖稳定的 mutate，send 才真正被 memo
  const { mutate } = start;

  /** 发送一条用户消息：输入框、示例 chip、行内指令都走这里，文本原样提交、不拼接不改写。 */
  const send = useCallback(
    (message: string) => {
      if (!message || busy) {
        return;
      }
      mutate({
        message,
        pageContext: {
          page: pageContext.page,
          ...(pageContext.entityType && pageContext.entityId
            ? { selectedEntity: { type: pageContext.entityType, id: pageContext.entityId } }
            : {}),
        },
        clientCapabilities: { uiSchemaVersion: '1.0', components: [...COMPONENT_TYPES] },
      });
    },
    [busy, mutate, pageContext],
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

  return (
    <div className={styles['panel']}>
      <form className={styles['inputRow']} onSubmit={onSubmit}>
        <label htmlFor="agent-input" className={styles['srOnly']}>
          输入你的需求
        </label>
        <input
          id="agent-input"
          className={styles['input']}
          value={input}
          onChange={(e) => setInput(e.target.value)}
          placeholder="例如：帮我把这个订单退款"
          disabled={busy}
          maxLength={2000}
        />
        <Button type="submit" disabled={busy || input.trim().length === 0}>
          发送
        </Button>
      </form>

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

      {errText ? (
        <p role="alert" className={styles['error']}>
          {errText}
        </p>
      ) : null}

      {view.messages.length > 0 ? (
        <ul className={styles['messages']} aria-label="对话消息">
          {view.messages.map((m, i) => (
            <li key={i} data-role={m.role} className={styles['message']}>
              <span className={styles['role']}>{m.role === 'user' ? '我' : '助手'}</span>
              {m.text}
            </li>
          ))}
        </ul>
      ) : null}

      {view.tools.length > 0 ? (
        <ol className={styles['tools']} aria-label="工具进度">
          {view.tools.map((t) => (
            <li key={t.toolCallId} data-status={t.status} className={styles['tool']}>
              <span className={styles['toolName']}>{t.displayName}</span>
              <span className={styles['toolStatus']}>
                {t.status === 'selected' && '已选择'}
                {t.status === 'running' && '执行中…'}
                {t.status === 'succeeded' &&
                  `完成${t.durationMs !== undefined ? ` · ${t.durationMs}ms` : ''}`}
                {t.status === 'failed' && '失败'}
              </span>
              {t.summary ? <span className={styles['toolSummary']}>{t.summary}</span> : null}
            </li>
          ))}
        </ol>
      ) : null}

      {view.ui ? (
        <section className={styles['ui']} aria-live="polite">
          <SchemaRenderer ui={view.ui} onFormChange={onFormChange} onIntent={send} />
          {view.phase === 'waiting_confirmation' ? (
            <ActionBar actions={view.ui.actions} disabled={busy} onAction={onAction} />
          ) : null}
        </section>
      ) : null}

      {view.phase === 'failed' && view.failure ? (
        <p role="alert" className={styles['error']}>
          {view.failure.message}
        </p>
      ) : null}
      {view.phase === 'completed' && !view.ui ? <p className={styles['done']}>已完成</p> : null}
    </div>
  );
}
