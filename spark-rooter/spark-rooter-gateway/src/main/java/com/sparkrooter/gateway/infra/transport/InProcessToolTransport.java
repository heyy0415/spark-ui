package com.sparkrooter.gateway.infra.transport;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.contracts.tool.ToolTransport;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolHandler;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内传输：领域服务与内核同进程（单体内嵌形态），直接调用已注册的 {@link ToolHandler}。
 *
 * <p>承载本形态的全部寻址职责：handler 注册表从 Gateway 移到这里——「toolId@version → handler 实例」本就是 进程内寻址的细节，远程传输没有这个概念。
 *
 * <p>不做超时 / 重试 / 线程切换 / 宿主上下文传播：这些仍由 Gateway 的 {@code InvokeToolUseCase} 统一负责， 本类在 Gateway
 * 已准备好的工作线程里同步执行。
 */
public class InProcessToolTransport implements ToolTransport {

  private final Map<String, ToolHandler> handlers = new ConcurrentHashMap<>();

  @Override
  public ToolManifest.Protocol protocol() {
    return ToolManifest.Protocol.IN_PROCESS;
  }

  /**
   * 注册一个工具执行入口（构造期的 {@link ToolHandler} Bean 与启动期 {@code @SparkTool} 扫描出的适配器共用）。
   *
   * @throws IllegalStateException 同 toolId@version 二次注册 —— 启动期即失败，不留到运行时
   */
  public void register(ToolHandler handler) {
    String key = handler.toolId() + "@" + handler.version();
    if (handlers.putIfAbsent(key, handler) != null) {
      throw new IllegalStateException("duplicate tool handler for " + key);
    }
  }

  /** 已注册的工具数，供启动日志与自检使用。 */
  public int size() {
    return handlers.size();
  }

  @Override
  public JsonNode invoke(ToolManifest manifest, JsonNode args, ExecutionContext ctx) {
    ToolHandler handler = handlers.get(manifest.key());
    if (handler == null) {
      throw new ToolTransportException(
          ToolTransportException.Kind.NOT_FOUND, "no handler bound for " + manifest.key());
    }
    return handler.handle(args, ctx);
  }
}
