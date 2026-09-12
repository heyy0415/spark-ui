package com.sparkrooter.provider;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.spi.ExecutionContext;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.SharedSecretProviderAuth;
import com.sparkrooter.spi.ToolHandler;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * provider 执行端点（feat-provider-http-transport §3.1a.1 / §3.1a.2 / §3.5）。
 *
 * <p>三组断言对应三条跨进程语义要求：hub→provider 认证、幂等去重（防 hub 重试导致重复执行）、 返回前脱敏（防原文过网络与进 provider 日志）。
 */
final class ProviderInvokeControllerTest {

  private static final String SERVICE = "order-service";
  private static final String TOKEN = "s3cret";
  private static final String TOOL = "order.detail.get";
  private static final String VERSION = "1.0.0";

  private final SchemaValidator validator = new SchemaValidator(PlatformMapper.create());
  private final ProviderToolRegistry registry = new ProviderToolRegistry();
  private final ProviderIdempotencyStore idempotency = new ProviderIdempotencyStore();
  private final ProviderAuth auth = new SharedSecretProviderAuth(Map.of(SERVICE, TOKEN));

  private SparkProviderProperties props() {
    return new SparkProviderProperties(
        "http://hub:8080", SERVICE, "http://order:8080", TOKEN, "order-team", false);
  }

  private ProviderInvokeController controller() {
    return new ProviderInvokeController(registry, auth, props(), idempotency, validator);
  }

  private ObjectNode out(String k, String v) {
    return validator.mapper().createObjectNode().put(k, v);
  }

  private void registerTool(AtomicInteger calls, JsonNode output) {
    registry.register(
        validator.mapper().createObjectNode().put("toolId", TOOL).put("version", VERSION),
        new ToolHandler() {
          @Override
          public String toolId() {
            return TOOL;
          }

          @Override
          public String version() {
            return VERSION;
          }

          @Override
          public JsonNode handle(JsonNode args, ExecutionContext ctx) {
            calls.incrementAndGet();
            return output;
          }
        });
  }

  private ProviderInvokeController.InvokeRequest req(String key) {
    return new ProviderInvokeController.InvokeRequest(
        TOOL, VERSION, out("orderId", "10001"), "run_1", "tc_1", "sess_1", key, null);
  }

  // ---------------------------------------------------------------- 认证

  @Test
  void validTokenIsAccepted() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ResponseEntity<JsonNode> r = controller().invoke(req("k1"), TOKEN);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(calls).hasValue(1);
  }

  /** 无令牌 → 401，且**工具绝不执行**。 */
  @Test
  void missingTokenIsUnauthorizedAndToolNotExecuted() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ResponseEntity<JsonNode> r = controller().invoke(req("k2"), null);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(calls).as("认证失败时不得执行业务").hasValue(0);
  }

  @Test
  void wrongTokenIsUnauthorizedAndToolNotExecuted() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ResponseEntity<JsonNode> r = controller().invoke(req("k3"), "wrong");
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    assertThat(calls).hasValue(0);
  }

  // ---------------------------------------------------------------- 幂等（M-1）

  /**
   * 同 idempotencyKey 的第二次请求返回首次结果，**不重复执行业务**。
   *
   * <p>这是 M-1 的 provider 侧断言：hub 的 claim 覆盖不到网络重传与 hub 重试，那两种情况下请求 真的第二次到达
   * provider。没有这一层，`idempotency=required` 只是一句声明。
   */
  @Test
  void sameIdempotencyKeyDoesNotExecuteTwice() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ProviderInvokeController c = controller();

    ResponseEntity<JsonNode> first = c.invoke(req("same-key"), TOKEN);
    ResponseEntity<JsonNode> second = c.invoke(req("same-key"), TOKEN);

    assertThat(calls).as("重复 key 不得重复执行").hasValue(1);
    assertThat(second.getBody()).isEqualTo(first.getBody());
  }

  @Test
  void differentIdempotencyKeysExecuteSeparately() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ProviderInvokeController c = controller();
    c.invoke(req("k-a"), TOKEN);
    c.invoke(req("k-b"), TOKEN);
    assertThat(calls).hasValue(2);
  }

  // ---------------------------------------------------------------- 脱敏（M-2）

  /**
   * 敏感键在**返回前**已脱敏。
   *
   * <p>M-2：脱敏点若留在 hub（接收端），原文已经过网络、已进 provider 日志与链路追踪。
   */
  @Test
  void sensitiveKeysAreRedactedBeforeLeavingProvider() {
    AtomicInteger calls = new AtomicInteger();
    ObjectNode payload = validator.mapper().createObjectNode();
    payload.put("orderId", "10001").put("token", "real-token").put("password", "hunter2");
    registerTool(calls, payload);

    JsonNode body = controller().invoke(req("k4"), TOKEN).getBody();

    assertThat(body.path("token").asText()).isEqualTo("***");
    assertThat(body.path("password").asText()).isEqualTo("***");
    assertThat(body.path("orderId").asText()).as("非敏感字段保持原值").isEqualTo("10001");
  }

  /** 嵌套对象与数组内的敏感键同样脱敏（与 hub 侧 redact 同口径）。 */
  @Test
  void redactionRecursesIntoNestedObjectsAndArrays() {
    AtomicInteger calls = new AtomicInteger();
    ObjectNode payload = validator.mapper().createObjectNode();
    payload.putObject("nested").put("secret", "inner");
    payload.putArray("items").addObject().put("apiKey", "in-array");
    registerTool(calls, payload);

    JsonNode body = controller().invoke(req("k5"), TOKEN).getBody();

    assertThat(body.path("nested").path("secret").asText()).isEqualTo("***");
    assertThat(body.path("items").get(0).path("apiKey").asText()).isEqualTo("***");
  }

  // ---------------------------------------------------------------- 错误映射

  /** 本 provider 未注册该工具 → 404（hub 的 Registry 与本进程不同步）。 */
  @Test
  void unknownToolIsNotFound() {
    ResponseEntity<JsonNode> r = controller().invoke(req("k6"), TOKEN);
    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
  }

  /** 工具自身抛异常 → 500，且响应体不含异常详情（不泄漏 provider 实现）。 */
  @Test
  void toolFailureIsInternalErrorWithoutLeakingDetails() {
    registry.register(
        validator.mapper().createObjectNode().put("toolId", TOOL).put("version", VERSION),
        new ToolHandler() {
          @Override
          public String toolId() {
            return TOOL;
          }

          @Override
          public String version() {
            return VERSION;
          }

          @Override
          public JsonNode handle(JsonNode args, ExecutionContext ctx) {
            throw new IllegalStateException("internal db connection string leaked here");
          }
        });

    ResponseEntity<JsonNode> r = controller().invoke(req("k7"), TOKEN);

    assertThat(r.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    assertThat(r.getBody()).as("不回显异常详情").isNull();
  }

  /** 失败不写入幂等缓存：失败可以重试，不该被缓存成终态。 */
  @Test
  void failureIsNotCachedAsIdempotentResult() {
    AtomicInteger calls = new AtomicInteger();
    registry.register(
        validator.mapper().createObjectNode().put("toolId", TOOL).put("version", VERSION),
        new ToolHandler() {
          @Override
          public String toolId() {
            return TOOL;
          }

          @Override
          public String version() {
            return VERSION;
          }

          @Override
          public JsonNode handle(JsonNode args, ExecutionContext ctx) {
            if (calls.incrementAndGet() == 1) {
              throw new IllegalStateException("transient");
            }
            return validator.mapper().createObjectNode().put("status", "paid");
          }
        });
    ProviderInvokeController c = controller();

    assertThat(c.invoke(req("retry-key"), TOKEN).getStatusCode())
        .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    // 同 key 再来一次：因为失败未缓存，业务会真的重试并成功
    assertThat(c.invoke(req("retry-key"), TOKEN).getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(calls).hasValue(2);
  }

  /** 缺必填上下文字段 → 应为 400（客户端错误），不是 500 + 堆栈。 */
  @org.junit.jupiter.api.Test
  void missingRequiredContextFieldIsClientError() {
    AtomicInteger calls = new AtomicInteger();
    registerTool(calls, out("status", "paid"));
    ProviderInvokeController.InvokeRequest bad =
        new ProviderInvokeController.InvokeRequest(
            TOOL, VERSION, out("orderId", "10001"), null, "tc_1", "sess_1", "k-bad", null);

    ResponseEntity<JsonNode> r = controller().invoke(bad, TOKEN);

    assertThat(r.getStatusCode().is4xxClientError()).as("缺 runId 是客户端错误，不该 500").isTrue();
    assertThat(calls).hasValue(0);
  }
}
