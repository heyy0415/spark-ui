package com.sparkrooter.starter;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.spi.SessionIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * starter 不能「默认不安全」：宿主未提供 SessionIdResolver → 启动失败并给出两条出路；显式演示开关 → demo 实现；宿主 Bean → 默认实现让位。 整体加载
 * SparkRooterAutoConfiguration（贴近宿主真实启动，0 个 @SparkTool、无 Web）。
 */
final class SessionIdResolverFailFastTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SparkRooterAutoConfiguration.class));

  @Test
  void missingHostResolverFailsStartupWithGuidance() {
    runner.run(
        ctx -> {
          assertThat(ctx).hasFailed();
          Throwable root = ctx.getStartupFailure();
          while (root.getCause() != null) {
            root = root.getCause();
          }
          assertThat(root)
              .isInstanceOf(IllegalStateException.class)
              .hasMessageContaining("SessionIdResolver")
              .hasMessageContaining("spark.runtime.demo-session-resolver=true");
        });
  }

  @Test
  void demoSwitchEnablesConversationIdResolver() {
    runner
        .withPropertyValues("spark.runtime.demo-session-resolver=true")
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(SessionIdResolver.class);
              assertThat(ctx.getBean(SessionIdResolver.class).resolve("conv-9"))
                  .isEqualTo("conv-9");
            });
  }

  @Test
  void hostResolverBeanTakesPrecedenceWithoutSwitch() {
    runner
        .withUserConfiguration(HostResolver.class)
        .run(
            ctx -> {
              assertThat(ctx).hasNotFailed().hasSingleBean(SessionIdResolver.class);
              assertThat(ctx.getBean(SessionIdResolver.class).resolve("conv-9"))
                  .isEqualTo("user-1:conv-9");
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class HostResolver {
    @Bean
    SessionIdResolver hostSessionIdResolver() {
      return conversationId -> "user-1:" + conversationId;
    }
  }
}
