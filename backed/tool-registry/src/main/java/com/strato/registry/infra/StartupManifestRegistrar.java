package com.strato.registry.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.contracts.model.ToolManifest;
import com.strato.registry.application.RegisterToolUseCase;
import com.strato.registry.domain.ToolVersionConflictException;
import com.strato.spi.ToolManifestSource;
import com.strato.spi.ToolNameSink;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动注册：遍历所有领域模块暴露的 ToolManifestSource（platform-spi 端口），逐个注册。 领域模块因此不依赖 registry；依赖方向 domains → spi
 * ← registry。
 */
@Component
public class StartupManifestRegistrar {

  private static final Logger log = LoggerFactory.getLogger(StartupManifestRegistrar.class);

  private final List<ToolManifestSource> sources;
  private final RegisterToolUseCase register;
  private final List<ToolNameSink> nameSinks;

  public StartupManifestRegistrar(
      List<ToolManifestSource> sources,
      RegisterToolUseCase register,
      List<ToolNameSink> nameSinks) {
    this.sources = sources;
    this.register = register;
    this.nameSinks = nameSinks;
  }

  /** 最高优先级：@Order 必须标在监听方法上，标在类上对 @EventListener 无效。 */
  @Order(Integer.MIN_VALUE)
  @EventListener(ApplicationReadyEvent.class)
  public void onReady() {
    int count = 0;
    for (ToolManifestSource src : sources) {
      for (JsonNode manifest : src.manifests()) {
        try {
          ToolManifest registered = register.execute(manifest);
          // 回填用户可读名称给 runtime（spi ToolNameSink），displayName 不再在 runtime 硬编码
          nameSinks.forEach(sink -> sink.register(registered.toolId(), registered.name()));
          count++;
        } catch (ToolVersionConflictException e) {
          // 同一 Manifest 被两个 source 重复暴露属配置错误，记录后继续
          log.warn("startup registration skipped duplicate {}", e.key());
        }
      }
    }
    log.info("startup registration done: {} tools from {} sources", count, sources.size());
  }
}
