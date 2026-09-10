package com.sparkrooter.webmvc;

import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.SessionIdResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主端口的默认实现（宿主定义同类型 Bean 即覆盖）。两者都只适合本地演示，构造期 WARN：
 *
 * <ul>
 *   <li>SessionIdResolver 返回 conversationId —— 前端生成、可伪造，等于无隔离；生产必须绑宿主登录态。
 *   <li>RunContextPropagator no-op —— 宿主 ThreadLocal / SecurityContext 不会带到 spark 工作线程。
 * </ul>
 *
 * T08 起由 starter 装配；此处先以 @Configuration 存在于 web-mvc。
 */
@Configuration
public class HostPortDefaults {

  private static final Logger log = LoggerFactory.getLogger(HostPortDefaults.class);

  @Bean
  @ConditionalOnMissingBean(SessionIdResolver.class)
  public SessionIdResolver sparkRooterSessionIdResolver() {
    log.warn("SessionIdResolver 为 demo 实现（sessionId = conversationId，无隔离）；生产必须由宿主实现为绑定自己的登录态");
    return conversationId -> conversationId;
  }

  @Bean
  @ConditionalOnMissingBean(RunContextPropagator.class)
  public RunContextPropagator sparkRooterRunContextPropagator() {
    log.warn("RunContextPropagator 为 no-op：宿主请求线程的 ThreadLocal / SecurityContext 不会传播到 spark 工作线程");
    return new RunContextPropagator() {
      @Override
      public Object capture() {
        return null;
      }

      @Override
      public void restore(Object captured) {}

      @Override
      public void clear() {}
    };
  }
}
