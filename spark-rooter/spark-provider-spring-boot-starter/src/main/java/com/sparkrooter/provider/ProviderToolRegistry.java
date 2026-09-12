package com.sparkrooter.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * provider 本地的工具表：{@code toolId@version → handler + 待推送的 Manifest}。
 *
 * <p>与 hub 侧 Registry 的区别：本类<b>不做能力发现</b>（provider 不参与规划），只做两件事—— 启动期把 Manifest 交给 {@link
 * ManifestPublisher} 推给 hub，运行期按 hub 的调用找到 handler 执行。
 *
 * <p>写入发生在启动期单线程（{@code afterSingletonsInstantiated}，早于 web 容器接受请求），之后只读。 仍用并发容器而非普通 Map：读发生在 web
 * 请求线程，普通 {@code LinkedHashMap} 的写入对这些线程 <b>没有 happens-before 保证</b>（阶段 4 评审 F-2）。与 hub 侧 {@code
 * InProcessToolTransport} 口径一致。
 */
public final class ProviderToolRegistry {

  private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();
  private final List<JsonNode> manifests = new CopyOnWriteArrayList<>();

  /**
   * 注册一个工具。
   *
   * @throws IllegalStateException 同 toolId@version 重复注册 —— 启动期即失败
   */
  public void register(JsonNode manifest, ToolHandler handler) {
    String key = handler.toolId() + "@" + handler.version();
    if (handlers.putIfAbsent(key, handler) != null) {
      throw new IllegalStateException("duplicate tool handler for " + key);
    }
    manifests.add(manifest);
  }

  /** 待推送的 Manifest（顺序与注册顺序一致，便于日志比对）。 */
  public List<JsonNode> manifests() {
    return List.copyOf(manifests);
  }

  public int size() {
    return handlers.size();
  }

  /**
   * 执行 hub 指定的工具。
   *
   * @throws ToolTransportException 本 provider 未注册该工具（hub 的 Registry 与本进程不同步， 例如 provider 发布回滚但 hub
   *     的注册未清理）
   */
  public JsonNode invoke(String toolId, String version, JsonNode args, ExecutionContext ctx) {
    ToolHandler handler = handlers.get(toolId + "@" + version);
    if (handler == null) {
      throw new ToolTransportException(
          ToolTransportException.Kind.NOT_FOUND,
          "provider has no handler for " + toolId + "@" + version);
    }
    return handler.handle(args, ctx);
  }
}
