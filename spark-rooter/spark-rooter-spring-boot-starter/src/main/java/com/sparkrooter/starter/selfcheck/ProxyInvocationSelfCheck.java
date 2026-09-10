package com.sparkrooter.starter.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.gateway.application.InvokeToolUseCase;
import com.sparkrooter.spi.SelfCheck;
import com.sparkrooter.spi.annotation.SparkTool;
import java.util.concurrent.atomic.AtomicInteger;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 代理调用自检（评审 M-1）：starter 自带一个 @SparkTool 测试 Bean 与一个 @Around 计数切面；经 Gateway 调用一次后切面计数必须恰为 1， 证明
 * Gateway 反射调用走的是 Spring 代理、宿主方法级切面会生效。测试工具 id 为 spark.selfcheck.echo，与业务领域无关。
 */
public final class ProxyInvocationSelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(ProxyInvocationSelfCheck.class);
  public static final String TOOL_ID = "spark.selfcheck.echo";
  public static final String VERSION = "1.0.0";

  /** 测试工具 Bean：原样回显。 */
  public static class EchoTools {
    public record In(
        @com.sparkrooter.spi.annotation.SparkParam(description = "回显文本") String text) {}

    public record Out(String text) {}

    @SparkTool(
        id = TOOL_ID,
        version = VERSION,
        domain = "spark",
        name = "自检回显",
        description = "starter 自检专用：原样回显输入文本，证明经代理调用时切面生效。")
    public Out echo(In in) {
      return new Out(in.text());
    }
  }

  /** 计数切面：只切 EchoTools.echo。 */
  @Aspect
  public static class CountingAspect {
    private final AtomicInteger count = new AtomicInteger();

    @Around(
        "execution(* com.sparkrooter.starter.selfcheck.ProxyInvocationSelfCheck.EchoTools.echo(..))")
    public Object around(ProceedingJoinPoint pjp) throws Throwable {
      count.incrementAndGet();
      return pjp.proceed();
    }

    public int count() {
      return count.get();
    }
  }

  private final InvokeToolUseCase gateway;
  private final CountingAspect aspect;
  private final com.fasterxml.jackson.databind.ObjectMapper mapper;

  public ProxyInvocationSelfCheck(
      InvokeToolUseCase gateway,
      CountingAspect aspect,
      com.fasterxml.jackson.databind.ObjectMapper mapper) {
    this.gateway = gateway;
    this.aspect = aspect;
    this.mapper = mapper;
  }

  @Override
  public String name() {
    return "proxy invocation";
  }

  @Override
  public void run() {
    int before = aspect.count();
    ObjectNode args = mapper.createObjectNode();
    args.put("text", "ping");
    ToolInvoke.Response resp =
        gateway.invoke(
            new ToolInvoke.Request(
                TOOL_ID,
                VERSION,
                args,
                new ToolInvoke.ExecutionContext(
                    "run_selfcheck", "tc_proxy", "selfcheck", "selfcheck-proxy", null)));
    if (resp.status() != com.sparkrooter.contracts.model.SseEvent.ToolStatus.succeeded) {
      throw new IllegalStateException("echo tool failed: " + resp.error());
    }
    JsonNode out = resp.output();
    if (!"ping".equals(out.path("text").asText())) {
      throw new IllegalStateException("echo tool returned " + out);
    }
    int hits = aspect.count() - before;
    if (hits != 1) {
      throw new IllegalStateException(
          "aspect around @SparkTool method fired "
              + hits
              + " times (expected 1): Gateway is not invoking through the Spring proxy");
    }
    log.info("selfcheck: proxy invocation OK (aspect fired once)");
  }
}
