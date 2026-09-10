package com.sparkrooter.spi;

/**
 * 把一次前端请求映射为会话隔离键：确认令牌与 Run 查询都按它隔离（他人 sessionId 看不到、也确认不了你的 Run）。
 *
 * <p>默认实现返回 conversationId（前端生成、可伪造），<b>等于无隔离，只适合本地演示</b>；生产宿主必须实现为绑自己的登录态（如 SecurityContextHolder
 * 的用户 ID 或网关注入的会话串）。在请求线程调用。
 */
public interface SessionIdResolver {
  String resolve(String conversationId);
}
