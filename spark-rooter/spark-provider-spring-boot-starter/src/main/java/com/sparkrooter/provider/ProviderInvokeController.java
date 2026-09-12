package com.sparkrooter.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.tool.ToolTransportException;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ProviderAuth;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * provider 的执行端点：接收 hub 的工具调用。
 *
 * <p>只做三件事——认证、幂等去重、调用本地 handler。**不做** Schema 校验 / 访问策略 / 重试 / 审计： 那些治理全在 hub 的 Gateway，provider
 * 重复一遍既无必要也会掩盖 hub 侧的问题。
 *
 * <p>两处 provider 专属职责（hub 做不到的）：
 *
 * <ul>
 *   <li><b>幂等去重</b>：hub 的 claim 只覆盖 hub 侧重复发起，覆盖不到网络重传与 hub 重试。跨进程后 {@code idempotency=required}
 *       若没人在 provider 侧兜住，就只是一句声明（§3.1a.1）
 *   <li><b>返回前脱敏</b>：hub 的脱敏发生在收到响应之后，那时数据已过网络、已进 provider 日志与链路追踪。 脱敏点必须前移到发送端（§3.1a.2）
 * </ul>
 */
@RestController
// 路径固定不可配：hub 侧 HttpToolTransport.INVOKE_PATH 是同一常量。若这里做成可配，
// 改了 base-path 就会让 hub 静默 404——「看起来能配、实际配了就坏」比不能配更糟（阶段 4 评审 F-3）。
@RequestMapping(ProviderInvokeController.BASE_PATH)
public class ProviderInvokeController {

  private static final Logger log = LoggerFactory.getLogger(ProviderInvokeController.class);

  /** 端点前缀；与 hub 侧 {@code HttpToolTransport.INVOKE_PATH} 必须一致。 */
  public static final String BASE_PATH = "/spark/tools";

  /** 与 hub 侧 Gateway 同一口径；两处必须一致，否则同一字段在一侧脱敏另一侧不脱。 */
  static final Set<String> SENSITIVE_KEYS = Set.of("password", "token", "secret", "apiKey");

  private static final String REDACTED = "***";

  private final ProviderToolRegistry registry;
  private final ProviderAuth auth;
  private final SparkProviderProperties props;
  private final ProviderIdempotencyStore idempotency;
  private final ObjectMapper mapper;

  public ProviderInvokeController(
      ProviderToolRegistry registry,
      ProviderAuth auth,
      SparkProviderProperties props,
      ProviderIdempotencyStore idempotency,
      SchemaValidator validator) {
    this.registry = registry;
    this.auth = auth;
    this.props = props;
    this.idempotency = idempotency;
    // 与 hub 同源的平台 mapper（PlatformMapper），保证两侧序列化口径一致
    this.mapper = validator.mapper();
  }

  /** hub → provider 的调用体。字段与 {@code ExecutionContext} 对齐；全部为 String（ID 规范）。 */
  public record InvokeRequest(
      String toolId,
      String toolVersion,
      JsonNode arguments,
      String runId,
      String toolCallId,
      String sessionId,
      String idempotencyKey,
      String traceId) {}

  @PostMapping("/invoke")
  public ResponseEntity<JsonNode> invoke(
      @RequestBody InvokeRequest req,
      @RequestHeader(value = ProviderAuth.HEADER, required = false) String token) {

    if (!auth.verify(props.serviceName(), token)) {
      log.warn(
          "provider_invoke_denied toolId={} runId={} reason=bad_token", req.toolId(), req.runId());
      return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
    }

    // ExecutionContext 对四个必填字段 fail-fast。请求体来自网络，缺字段是**客户端错误**，
    // 必须 400 而不是让 IllegalArgumentException 冒成 500 + 堆栈（阶段 4 评审 F-1）。
    ExecutionContext ctx;
    try {
      ctx =
          new ExecutionContext(
              req.runId(), req.toolCallId(), req.sessionId(), req.idempotencyKey(), req.traceId());
    } catch (IllegalArgumentException e) {
      log.warn("provider_invoke_bad_request toolId={} reason={}", req.toolId(), e.getMessage());
      return ResponseEntity.badRequest().build();
    }

    // 同 key 已有结果 → 直接返回首次结果，不重复执行业务。
    // 这是跨进程的必要一层：hub 超时重试或网络重传都会让同一 key 到达两次。
    JsonNode replayed = idempotency.find(req.sessionId(), req.idempotencyKey());
    if (replayed != null) {
      log.info(
          "provider_invoke_replayed toolId={} runId={} key={}",
          req.toolId(),
          req.runId(),
          req.idempotencyKey());
      return ResponseEntity.ok(replayed);
    }

    try {
      JsonNode output = registry.invoke(req.toolId(), req.toolVersion(), req.arguments(), ctx);
      JsonNode safe = redact(output, mapper);
      idempotency.remember(req.sessionId(), req.idempotencyKey(), safe);
      log.info("provider_invoke_ok toolId={} runId={}", req.toolId(), req.runId());
      return ResponseEntity.ok(safe);
    } catch (ToolTransportException e) {
      log.warn(
          "provider_invoke_not_found toolId={} runId={} kind={}",
          req.toolId(),
          req.runId(),
          e.kind());
      return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    } catch (RuntimeException e) {
      // 工具自身失败 → 500。异常详情只进本地日志，不回给 hub（不泄漏 provider 实现细节）
      log.error("provider_invoke_failed toolId={} runId={}", req.toolId(), req.runId(), e);
      return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
    }
  }

  /**
   * 递归脱敏：敏感键的字符串值替换为 {@code ***}。
   *
   * <p>在**发送前**做，而不是等 hub 收到再做——脱敏点选在接收端时，原文已经过网络、已进 provider 本地日志与链路追踪（§3.1a.2，公司规范要求敏感信息先脱敏）。
   *
   * <p>实现与 hub 侧 Gateway 的 {@code redact} 逐行一致（含数组递归），两处必须同口径， 否则同一字段会在一侧脱敏、另一侧留原文。
   */
  static JsonNode redact(JsonNode node, ObjectMapper mapper) {
    if (node == null) {
      return null;
    }
    if (node.isObject()) {
      ObjectNode copy = node.deepCopy();
      copy.fieldNames()
          .forEachRemaining(
              k -> {
                if (SENSITIVE_KEYS.contains(k) && copy.get(k).isTextual()) {
                  copy.put(k, REDACTED);
                } else {
                  copy.set(k, redact(copy.get(k), mapper));
                }
              });
      return copy;
    }
    if (node.isArray()) {
      ArrayNode arr = mapper.createArrayNode();
      node.forEach(n -> arr.add(redact(n, mapper)));
      return arr;
    }
    return node;
  }
}
