package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.SessionIdResolver;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 示例宿主的「真实」SessionIdResolver（只在 e2e profile 启用，默认仍用 starter 的 demo 实现以演示 WARN）：会话键 = 当前用户 + 会话号。
 * 于是 A 的确认令牌拿到 B 的请求头下提交 → sessionId 不一致 → CONFIRMATION_REJECTED（e2e ㉔ 的真实 HTTP 级断言）；
 * 会话记忆也按 (sessionId, conversationId) 隔离，B 用 A 的 conversationId 拿不到 A 的实体。真实宿主在这里返回登录态里的用户 / 会话标识。
 */
@Component
@Profile("e2e")
public class DemoSessionIdResolver implements SessionIdResolver {
  @Override
  public String resolve(String conversationId) {
    return DemoUserContext.userId() + ":" + conversationId;
  }
}
