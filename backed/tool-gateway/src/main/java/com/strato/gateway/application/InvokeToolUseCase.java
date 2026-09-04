package com.strato.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.ToolInvoke;
import com.strato.contracts.model.ToolManifest;
import com.strato.gateway.domain.ArgsDigest;
import com.strato.gateway.domain.AuditSink;
import com.strato.gateway.domain.GatewayException;
import com.strato.gateway.domain.IdempotencyStore;
import com.strato.gateway.domain.RetryPolicy;
import com.strato.spi.ExecutionContext;
import com.strato.spi.Principal;
import com.strato.spi.PrincipalPermissionResolver;
import com.strato.spi.ToolHandler;
import com.strato.spi.ToolResolver;
import java.util.List;
import java.util.Map;
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
import org.springframework.stereotype.Service;

/**
 * 执行面唯一入口（agent-safety §5）。顺序固定： 输入 Schema 校验 → 鉴权 → 幂等 → 寻址 → 调用（超时 / 重试按 Manifest）→ 输出 Schema 校验
 * → 脱敏 → 审计。
 *
 * <p>Gateway 不做规划、不选工具；ToolHandler 由 Spring 注入，pom 不依赖任何领域模块。
 */
@Service
public class InvokeToolUseCase {

  private static final Logger log = LoggerFactory.getLogger(InvokeToolUseCase.class);
  private static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "secret", "apiKey");

  private final ToolResolver resolver;
  private final PrincipalPermissionResolver permissions;
  private final IdempotencyStore idempotency;
  private final AuditSink audit;
  private final SchemaValidator validator;
  private final Map<String, ToolHandler> handlers;
  private final ExecutorService executor;

  public InvokeToolUseCase(
      ToolResolver resolver,
      PrincipalPermissionResolver permissions,
      IdempotencyStore idempotency,
      AuditSink audit,
      SchemaValidator validator,
      List<ToolHandler> handlerBeans,
      ExecutorService toolExecutor) {
    this.resolver = resolver;
    this.permissions = permissions;
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
    String principalText = ec.userId() + "@" + ec.tenantId();
    try {
      ToolInvoke.Response resp = pipeline(req, ec);
      audit.record(
          new AuditSink.Entry(
              ec.runId(),
              ec.toolCallId(),
              req.toolId(),
              req.toolVersion(),
              principalText,
              digest,
              resp.status().name(),
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
              principalText,
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

  private ToolInvoke.Response pipeline(ToolInvoke.Request req, ToolInvoke.ExecutionContext ec) {
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

    // 3. 鉴权
    Principal principal = new Principal(ec.userId(), ec.tenantId());
    if (!permissions.permissionsOf(principal).contains(manifest.authorization().permission())) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.FORBIDDEN,
          "missing permission " + manifest.authorization().permission());
    }

    // 4. 幂等（仅对声明 required 的工具）
    boolean idem = manifest.execution().idempotency() == ToolManifest.Idempotency.required;
    if (idem) {
      var cached = idempotency.find(ec.tenantId(), ec.idempotencyKey());
      if (cached.isPresent()) {
        log.info("idempotent replay toolId={} key={}", req.toolId(), ec.idempotencyKey());
        return cached.get();
      }
    }

    // 5. 调用（超时 + 按策略重试）
    ToolHandler handler = handlers.get(manifest.key());
    if (handler == null) {
      throw new GatewayException(
          ToolInvoke.ErrorCode.TOOL_NOT_FOUND, "no handler bound for " + manifest.key());
    }
    ExecutionContext ctx =
        new ExecutionContext(
            ec.runId(), ec.toolCallId(), principal, ec.idempotencyKey(), ec.traceId());
    JsonNode output = callWithRetry(handler, req.arguments(), ctx, manifest);

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
    return idem ? idempotency.putIfAbsent(ec.tenantId(), ec.idempotencyKey(), resp) : resp;
  }

  private JsonNode callWithRetry(
      ToolHandler handler, JsonNode args, ExecutionContext ctx, ToolManifest manifest) {
    int retries = RetryPolicy.allowedRetries(manifest);
    long timeoutMs = manifest.execution().timeoutMs();
    GatewayException last = null;
    for (int attempt = 0; attempt <= retries; attempt++) {
      try {
        // 用 ExecutorService.submit 而非 CompletableFuture：后者的 cancel(true) 不会中断工作线程
        Future<JsonNode> f = executor.submit(() -> handler.handle(args, ctx));
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
