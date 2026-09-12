package com.sparkrooter.spi.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ToolContext;
import com.sparkrooter.spi.ToolHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * @SparkTool 方法 → ToolHandler 适配。持有的是 ApplicationContext 里的<b>代理</b> Bean 与可在代理上调用的
 * Method（安全关键：宿主方法级切面因此生效）。 JsonNode → In 用平台 ObjectMapper；宿主异常只保留类名 + 首行（不带 cause，避免栈里的业务数据进日志 /
 * 响应）。
 */
public final class AnnotatedToolHandler implements ToolHandler {

  private final String toolId;
  private final String version;
  private final Object proxy;
  private final Method invocable;
  private final Class<?> inType;
  private final boolean wantsContext;
  private final ObjectMapper mapper;

  public AnnotatedToolHandler(
      String toolId,
      String version,
      Object proxy,
      Method invocable,
      Class<?> inType,
      boolean wantsContext,
      ObjectMapper mapper) {
    this.toolId = toolId;
    this.version = version;
    this.proxy = proxy;
    this.invocable = invocable;
    this.inType = inType;
    this.wantsContext = wantsContext;
    this.mapper = mapper;
  }

  @Override
  public String toolId() {
    return toolId;
  }

  @Override
  public String version() {
    return version;
  }

  @Override
  public JsonNode handle(JsonNode args, ExecutionContext ctx) {
    Object in;
    try {
      in = mapper.treeToValue(args, inType);
    } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
      throw new IllegalArgumentException("arguments do not bind to " + inType.getSimpleName());
    }
    Object out;
    try {
      out =
          wantsContext
              ? invocable.invoke(proxy, in, ToolContext.from(ctx))
              : invocable.invoke(proxy, in);
    } catch (InvocationTargetException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      String msg =
          cause.getMessage() == null ? "" : cause.getMessage().lines().findFirst().orElse("");
      throw new IllegalStateException(cause.getClass().getSimpleName() + ": " + msg);
    } catch (IllegalAccessException e) {
      throw new IllegalStateException("cannot invoke " + invocable, e);
    }
    return mapper.valueToTree(out);
  }
}
