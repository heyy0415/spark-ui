import { useMemo, useState } from 'react';
import { useSearchParams } from 'react-router';
import type { Principal } from '@entities/agent-run';
import { AgentChatPanel, PageContextQuerySchema } from '@features/agent-chat';
import styles from './AgentPage.module.css';

/** 首期固定身份（真实登录为后续 change）。 */
const PRINCIPAL: Principal = { userId: 'user_001', tenantId: 'tenant_001' };

/** /agent?page=order-detail&entityType=order&entityId=10001 —— query 经 Zod 校验后作为 pageContext（不可信输入）。 */
export function AgentPage() {
  const [params] = useSearchParams();
  const pageContext = useMemo(() => {
    const raw = {
      page: params.get('page') ?? undefined,
      entityType: params.get('entityType') ?? undefined,
      entityId: params.get('entityId') ?? undefined,
    };
    const parsed = PageContextQuerySchema.safeParse(raw);
    return parsed.success ? parsed.data : PageContextQuerySchema.parse({});
  }, [params]);

  // 会话 ID 只在挂载时生成一次；用 useState 初始化函数避免在渲染期调用 Date.now（react/purity）
  const [conversationId] = useState(() => `conv_${Date.now().toString(36)}`);

  return (
    <section className={styles['wrap']} aria-labelledby="agent-title">
      <h1 id="agent-title" className={styles['title']}>
        {/* 智能助手 */}
      </h1>
      <p className={styles['context']}>
        当前上下文：{pageContext.page}
        {pageContext.entityType && pageContext.entityId
          ? ` · ${pageContext.entityType}/${pageContext.entityId}`
          : ''}
      </p>
      <AgentChatPanel
        conversationId={conversationId}
        principal={PRINCIPAL}
        pageContext={pageContext}
      />
    </section>
  );
}
