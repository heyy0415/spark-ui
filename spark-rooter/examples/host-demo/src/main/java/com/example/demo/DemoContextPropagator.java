package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.RunContextPropagator;
import org.springframework.stereotype.Component;

/**
 * 宿主实现 spi RunContextPropagator：把请求线程的 DemoUserContext 带到 spark 的 agent-run-* / gateway-tool-* 线程。
 * 真实宿主在这里搬 SecurityContext / MDC / 自己的 ThreadLocal。定义了本 Bean，starter 的 no-op 默认实现即不装配。
 */
@Component
public class DemoContextPropagator implements RunContextPropagator {

  @Override
  public Object capture() {
    return DemoUserContext.current().orElse(null);
  }

  @Override
  public void restore(Object captured) {
    if (captured instanceof DemoUserContext.DemoUser u) {
      DemoUserContext.set(u);
    } else {
      DemoUserContext.clear();
    }
  }

  @Override
  public void clear() {
    DemoUserContext.clear();
  }
}
