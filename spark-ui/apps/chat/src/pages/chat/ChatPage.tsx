import { useState } from 'react';
import { AgentChatPanel } from '@features/agent-chat';
import styles from './ChatPage.module.css';

/** 对话页：只有自然语言输入；无 URL 参数、无页面上下文、无身份（身份在宿主工程）。 */
export function ChatPage() {
  // 会话 ID 只在挂载时生成一次；用 useState 初始化函数避免在渲染期调用 Date.now（react/purity）
  const [conversationId] = useState(() => `conv_${Date.now().toString(36)}`);

  return (
    <section className={styles['wrap']} aria-labelledby="chat-title">
      <h1 id="chat-title" className={styles['title']}>
        {/* 智能助手 */}
      </h1>
      <AgentChatPanel conversationId={conversationId} />
    </section>
  );
}
