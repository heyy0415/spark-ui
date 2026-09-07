import { useState } from 'react';
import type { FormEvent } from 'react';
import type { Principal, UiAction } from '@entities/agent-run';
import { HttpError } from '@shared/api';
import { ActionBar, Button, SchemaRenderer } from '@shared/ui';
import { FormIncompleteError, useAgentRun } from '../api/useAgentRun';
import type { PageContextQuery } from '../model/runView';
import styles from './AgentChatPanel.module.css';

const COMPONENTS = [
  'Form',
  'Card',
  'Table',
  'ResultCard',
  'ConfirmationCard',
  'OrderCard',
  'RefundConfirmCard',
];

export interface AgentChatPanelProps {
  conversationId: string;
  principal: Principal;
  pageContext: PageContextQuery;
}

export function AgentChatPanel({ conversationId, principal, pageContext }: AgentChatPanelProps) {
  const [input, setInput] = useState('');
  const { view, start, submitAction, onFormChange, busy } = useAgentRun({
    conversationId,
    principal,
  });

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    const message = input.trim();
    if (!message || busy) {
      return;
    }
    start.mutate({
      message,
      pageContext: {
        page: pageContext.page,
        ...(pageContext.entityType && pageContext.entityId
          ? { selectedEntity: { type: pageContext.entityType, id: pageContext.entityId } }
          : {}),
      },
      clientCapabilities: { uiSchemaVersion: '1.0', components: COMPONENTS },
    });
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

      {errText ? (
        <p role="alert" className={styles['error']}>
          {errText}
        </p>
      ) : null}

      {view.messages.length > 0 ? (
        <ul className={styles['messages']} aria-label="助手消息">
          {view.messages.map((m, i) => (
            <li key={i}>{m}</li>
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
          <SchemaRenderer ui={view.ui} onFormChange={onFormChange} />
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
