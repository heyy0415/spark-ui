package com.strato.runtime.application;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 时钟 Bean：令牌过期与时间戳统一来源，便于自检注入固定时钟。 */
@Configuration
public class RuntimeClockConfiguration {
  @Bean
  public Clock runtimeClock() {
    return Clock.systemUTC();
  }
}
