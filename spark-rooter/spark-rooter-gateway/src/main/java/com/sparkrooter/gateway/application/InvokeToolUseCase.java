package com.sparkrooter.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.gateway.api.ToolInvokePort;
import com.sparkrooter.gateway.domain.ArgsDigest;
import com.sparkrooter.gateway.domain.GatewayException;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.gateway.domain.IdempotencyStore.Claim;
import com.sparkrooter.gateway.domain.RetryPolicy;
import com.sparkrooter.spi.AuditSink;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolHandler;
import com.sparkrooter.spi.ToolResolver;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 执行面唯一入口（agent-safety §5）。顺序固定： 寻址 → 输入 Schema 校验 → 宿主访问策略（可选）→ 幂等 → 调用（超时 / 重试按 Manifest）→ 输出
 * Schema 校验 → 脱敏 → 审计。内核不做用户鉴权：宿主的方法级切面在反射调用时照常触发，宿主 ThreadLocal 经 RunContextPropagator 带到工具线程。
 *
 * <p>Gateway 不做规划、不选工具；ToolHandler 由 Spring 注入，pom 不依赖任何领域模块。
 */
@Service
public class InvokeToolUseCase implements ToolInvokePort {

  private static final Logger log = LoggerFactory.getLogger(InvokeToolUseCase.class);
  private static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "secret", "apiKey");

  private final ToolResolver resolver;
  private final ToolAccessPolicy access;
  private final RunContextPropagator propagator;
  private final IdempotencyStore idempotency;
  private final AuditSink audit;
  private final SchemaValidator validator;
  private final Map<String, ToolHandler> handlers;
  private final ExecutorService executor;

  /** pipeline 的结果：response + 是否为重放 / 等待得到（审计口径用，契约 status 不变）。 */
  private record Outcome(ToolInvoke.Response response, boolean replayed) {}

  public InvokeToolUseCase(
      ToolResolver resolver,
      ObjectProvider<ToolAccessPolicy> access,
      RunContextPropagator propagator,
      IdempotencyStore idempotency,
      AuditSink audit,
      SchemaValidator validator,
      List<ToolHandler> handlerBeans,
      ExecutorService toolExecutor) {
    this.resolver = resolver;
    // 宿主未定义策略 Bean → 全放行
    this.access = access.getIfAvailable(() -> (toolId, sessionId) -> true);
    this.propagator = propagator;
    this.idempotency = idempotency;
    this.audit = audit;
    this.validator = validator;
    this.handlers =
        handlerBeans.stream()
            .collect(
                Collectors.toUnmodifiableMap(
                    h -> h.toolId() + "@" + h.version(), Function.identity()));
    this.executor = toolExecutor;
    log.info("gateway handlers registered: {}", handlers.keySet());
  }

  /**
   * 与 {@link #execute} 相同的管线，但失败不抛异常而是返回契约 tool-invoke.response{status=failed, error.code}。
   * 供进程内调用方（Runtime 端口适配）使用，调用方无需依赖本模块 domain 包的异常类型。
   */
  @Override
  public ToolInvoke.Response invoke(ToolInvoke.Request req) {
    return executeToResponse(req);
  }

  public ToolInvoke.Response executeToResponse(ToolInvoke.Request req) {
    long t0 = System.nanoTime();
    try {
      return execute(req);
    } catch (GatewayException e) {
      long ms = (System.nanoTime() - t0) / 1_000_000;
      return ToolInvoke.Response.failed(
          req.executionContext().toolCallId(), ms, e.code(), e.getMessage());
    }
  }

  public ToolInvoke.Response execute(ToolInvoke.Request req) {
    ToolInvoke.ExecutionContext ec = req.executionContext();
    long start = System.nanoTime();
    MDC.put("runId", ec.runId());
    MDC.put("toolCallId", ec.toolCallId());
    if (ec.traceId() != null) {
      MDC.put("traceId", ec.traceId());
    }
    String digest = ArgsDigest.of(req.arguments().toString());
    try {
      Outcome out = pipeline(req, ec);
      ToolInvoke.Response resp = out.response();
      // 重放 / 等待拿到的结果审计为 replayed（仅日志口径，契约 status 不变），让 succeeded 恰好等于真实执行次数
      String auditStatus = out.replayed() ? "replayed" : resp.status().name();
      audit.record(
          new AuditSink.Entry(
              ec.runId(),
              ec.toolCallId(),
              req.toolId(),
              req.toolVersion(),
              ec.sessionId(),
              digest,
              auditStatus,
              resp.durationMs(),
              ec.traceId()));
      return resp;
    } catch (GatewayException e) {
      long ms = elapsedMs(start);
      audit.record(
          new AuditSink.Entry(
              ec.runId(),
              ec.toolCallId(),
              req.toolId(),
              req.toolVersion(),
              ec.sessionId(),
              digest,
              "failed:" + e.code().name(),
              ms,
              ec.traceId()));
      throw e;
    } finally {
      MDC.remove("runId");
      MDC.remove("toolCallId");
      MDC.remove("traceId");
    }
  }

  private Outcome pipeline(ToolInvoke.Request req, ToolInvoke.ExecutionContext ec) {
    long start = System.nanoTime();

    // 1. 寻址（先取 Manifest，后续校验需要它）
    JsonNode manifestJson =
        resolver
            .resolve(req.toolId(), req.toolVersion())
            .orElseThrow(
                () ->
                    new GatewayException(
                        ToolInvoke.ErrorCode.TOOL_NOT_FOUND,
                        "tool not registered: " + req.toolId() + "@" + req.toolVersion()));
    ToolManifest manifest = validator.mapper().convertValue(manifestJson, ToolManifest.class);

    // 2. 输入 Schema 校验
    Set<ValidationMessage> inErr =
        validator.validateWithInlineSchema(manifest.inputSchema(), req.arguments());
    if (!inErr.isEmpty()) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.INPUT_INVALID, "arguments invalid: " + summarize(inErr));
    }

    // 3. 宿主访问策略（可选；默认全放行）。用户级权限由宿主在工具方法上用切面做，这里不做
    if (!access.allowed(manifest.toolId(), ec.sessionId())) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.FORBIDDEN,
          "tool access denied by host policy: " + manifest.toolId());
    }

    // 4. 幂等（仅对声明 required 的工具）：先占位后填充。拿不到执行权的等待或重放
    boolean idem = manifest.execution().idempotency() == ToolManifest.Idempotency.required;
    if (idem) {
      Optional<ToolInvoke.Response> shared =
          claimOrAwait(req, ec, manifest.execution().timeoutMs());
      if (shared.isPresent()) {
        return new Outcome(shared.get(), true);
      }
      // 此处已持有 Owner：执行成功 complete；未 complete 就离开（含 Error）一律释放占位，否则同 key 永久悬挂
      boolean completed = false;
      try {
        ToolInvoke.Response resp = execute(req, ec, manifest, start);
        idempotency.complete(ec.sessionId(), ec.idempotencyKey(), resp);
        completed = true;
        return new Outcome(resp, false);
      } finally {
        if (!completed) {
          idempotency.release(ec.sessionId(), ec.idempotencyKey());
        }
      }
    }
    return new Outcome(execute(req, ec, manifest, start), false);
  }

  /**
   * 在 deadline 内反复 claim：Owner → empty（由调用方执行）；Replay / Awaiting 正常完成 → 他人的结果；Awaiting 被 release 唤醒
   * → 再 claim；deadline 到 → TIMEOUT。
   */
  private Optional<ToolInvoke.Response> claimOrAwait(
      ToolInvoke.Request req, ToolInvoke.ExecutionContext ec, long timeoutMs) {
    long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
    while (true) {
      Claim claim = idempotency.claim(ec.sessionId(), ec.idempotencyKey());
      switch (claim) {
        case Claim.Owner o -> {
          return Optional.empty();
        }
        case Claim.Replay r -> {
          log.info("idempotent replay toolId={} key={}", req.toolId(), ec.idempotencyKey());
          return Optional.of(r.response());
        }
        case Claim.Awaiting a -> {
          long remain = deadline - System.nanoTime();
          if (remain <= 0) {
            throw new GatewayException(
                ToolInvoke.ErrorCode.TIMEOUT,
                "idempotent owner did not finish in " + timeoutMs + "ms");
          }
          try {
            ToolInvoke.Response r = a.future().get(remain, TimeUnit.NANOSECONDS);
            log.info("idempotent await toolId={} key={}", req.toolId(), ec.idempotencyKey());
            return Optional.of(r);
          } catch (TimeoutException e) {
            throw new GatewayException(
                ToolInvoke.ErrorCode.TIMEOUT,
                "idempotent owner did not finish in " + timeoutMs + "ms",
                e);
          } catch (ExecutionException e) {
            // 执行者 release 了占位：循环重新 claim（可能成为 Owner）
            log.info(
                "idempotent owner released toolId={} key={}, re-claiming",
                req.toolId(),
                ec.idempotencyKey());
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GatewayException(ToolInvoke.ErrorCode.HANDLER_ERROR, "interrupted", e);
          }
        }
      }
    }
  }

  private ToolInvoke.Response execute(
      ToolInvoke.Request req, ToolInvoke.ExecutionContext ec, ToolManifest manifest, long start) {
    // 5. 调用（超时 + 按策略重试）
    ToolHandler handler = handlers.get(manifest.key());
    if (handler == null) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.TOOL_NOT_FOUND, "no handler bound for " + manifest.key());
    }
    ExecutionContext ctx =
        new ExecutionContext(
            ec.runId(), ec.toolCallId(), ec.sessionId(), ec.idempotencyKey(), ec.traceId());
    // 当前线程（Runtime 的 agent-run-* 或 HTTP 线程）里的宿主上下文，带到 tool-* 线程
    Object hostCtx = propagator.capture();
    JsonNode output = callWithRetry(handler, req.arguments(), ctx, manifest, hostCtx);

    // 6. 输出 Schema 校验
    Set<ValidationMessage> outErr =
        validator.validateWithInlineSchema(manifest.outputSchema(), output);
    if (!outErr.isEmpty()) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.OUTPUT_INVALID,
          "tool output violates outputSchema: " + summarize(outErr));
    }

    // 7. 脱敏
    JsonNode safe = redact(output);

    ToolInvoke.Response resp =
        ToolInvoke.Response.succeeded(ec.toolCallId(), elapsedMs(start), safe);
    return resp;
  }

  private JsonNode callWithRetry(
      ToolHandler handler,
      JsonNode args,
      ExecutionContext ctx,
      ToolManifest manifest,
      Object hostCtx) {
    int retries = RetryPolicy.allowedRetries(manifest);
    long timeoutMs = manifest.execution().timeoutMs();
    GatewayException last = null;
    for (int attempt = 0; attempt <= retries; attempt++) {
      try {
        // 用 ExecutorService.submit 而非 CompletableFuture：后者的 cancel(true) 不会中断工作线程
        Future<JsonNode> f =
            executor.submit(
                () -> {
                  propagator.restore(hostCtx);
                  try {
                    return handler.handle(args, ctx);
                  } finally {
                    propagator.clear();
                  }
                });
        try {
          return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
          // 超时中断 handler 线程，避免继续占用执行器
          f.cancel(true);
          throw e;
        }
      } catch (TimeoutException e) {
        last =
            new GatewayException(
                ToolInvoke.ErrorCode.TIMEOUT, "tool timed out after " + timeoutMs + "ms", e);
        log.warn(
            "tool timeout toolId={} attempt={}/{}", manifest.toolId(), attempt + 1, retries + 1);
      } catch (ExecutionException e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        last =
            new GatewayException(
                ToolInvoke.ErrorCode.HANDLER_ERROR, "tool failed: " + cause.getMessage(), cause);
        log.warn(
            "tool error toolId={} attempt={}/{} cause={}",
            manifest.toolId(),
            attempt + 1,
            retries + 1,
            cause.toString());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new GatewayException(ToolInvoke.ErrorCode.HANDLER_ERROR, "interrupted", e);
      }
    }
    throw last;
  }

  /** 递归脱敏：键名命中敏感词的字符串值替换为 "***"。 */
  private JsonNode redact(JsonNode node) {
    if (node.isObject()) {
      ObjectNode copy = node.deepCopy();
      copy.fieldNames()
          .forEachRemaining(
              k -> {
                if (SENSITIVE_KEYS.contains(k) && copy.get(k).isTextual()) {
                  copy.put(k, "***");
                } else {
                  copy.set(k, redact(copy.get(k)));
                }
              });
      return copy;
    }
    if (node.isArray()) {
      var arr = validator.mapper().createArrayNode();
      node.forEach(n -> arr.add(redact(n)));
      return arr;
    }
    return node;
  }

  private static String summarize(Set<ValidationMessage> errs) {
    return errs.stream()
        .limit(3)
        .map(v -> v.getInstanceLocation() + " " + v.getMessage())
        .collect(Collectors.joining("; "));
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
