package com.sparkrooter.starter;

import com.sparkrooter.spi.SelfCheck;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;

/**
 * 启动自检：遍历所有 SelfCheck Bean；任一失败即终止启动。只在 spark.selfcheck.enabled=true 时装配（{@link SelfCheckBeans}）。
 */
public class SelfCheckRunner {

  private static final Logger log = LoggerFactory.getLogger(SelfCheckRunner.class);

  private final List<SelfCheck> checks;

  public SelfCheckRunner(List<SelfCheck> checks) {
    this.checks = List.copyOf(checks);
  }

  /** 最低优先级：必须在 StartupManifestRegistrar 之后运行，后续自检依赖已注册的工具。 */
  @Order(Integer.MAX_VALUE)
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    log.info("selfcheck: running {} checks", checks.size());
    for (SelfCheck c : checks) {
      c.run();
      log.info("selfcheck: {} OK", c.name());
    }
  }
}
