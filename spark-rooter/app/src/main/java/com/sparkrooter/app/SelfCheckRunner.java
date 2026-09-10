package com.sparkrooter.app;

import com.sparkrooter.spi.SelfCheck;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 启动自检：遍历所有 SelfCheck Bean；任一失败即终止启动。受 spark.selfcheck.enabled 控制，生产建议关闭。 */
@Component
@EnableConfigurationProperties(SelfCheckProperties.class)
public class SelfCheckRunner {
  private static final Logger log = LoggerFactory.getLogger(SelfCheckRunner.class);

  private final List<SelfCheck> checks;
  private final SelfCheckProperties props;

  public SelfCheckRunner(List<SelfCheck> checks, SelfCheckProperties props) {
    this.checks = checks;
    this.props = props;
  }

  /** 最低优先级：必须在 StartupManifestRegistrar 之后运行，后续自检依赖已注册的工具。 */
  @Order(Integer.MAX_VALUE)
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    if (!props.enabled()) {
      log.info("selfcheck: disabled");
      return;
    }
    log.info("selfcheck: running {} checks", checks.size());
    for (SelfCheck c : checks) {
      c.run();
      log.info("selfcheck: {} OK", c.name());
    }
  }
}
