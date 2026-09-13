package com.sparkrooter.runtime.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.model.SseEvent;
import com.sparkrooter.contracts.model.ToolInvoke;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.port.LlmClient;
import com.sparkrooter.runtime.application.port.RunEventSink;
import com.sparkrooter.runtime.application.port.ToolGatewayClient;
import com.sparkrooter.runtime.application.port.ToolRegistryClient;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.infra.InMemoryConversationMemory;
import com.sparkrooter.runtime.infra.InMemoryRunRepository;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.ScreenBuilder;
import com.sparkrooter.spi.ScreenContext;
import com.sparkrooter.spi.UiNodes;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** RunOrchestrator 的全部协作者 Fake：脚本化、可录制、产出契约合法的屏与事件。 */
public final class Fakes {

  private Fakes() {}

  // ---------------------------------------------------------------- LlmClient

  /** 按队列依次返回 Decision；队列空则抛 IllegalStateException（说明测试脚本不完整）。 */
  public static final class FakeLlm implements LlmClient {
    private final List<Supplier<Decision>> script = new ArrayList<>();
    public final List<PlanRequest> requests = new ArrayList<>();

    public FakeLlm returns(Decision d) {
      script.add(() -> d);
      return this;
    }

    public FakeLlm throwsFailure(RuntimeException e) {
      script.add(
          () -> {
            throw e;
          });
      return this;
    }

    @Override
    public Decision plan(PlanRequest request) {
      requests.add(request);
      if (script.isEmpty()) {
        throw new IllegalStateException("FakeLlm script exhausted");
      }
      return script.remove(0).get();
    }

    @Override
    public String name() {
      return "fake";
    }
  }

  // ---------------------------------------------------------------- Registry

  public static final class FakeRegistry implements ToolRegistryClient {
    private final List<ToolSearch.ToolCandidate> tools;
    public final List<String> sessionIds = new ArrayList<>();

    public FakeRegistry(List<ToolSearch.ToolCandidate> tools) {
      this.tools = List.copyOf(tools);
    }

    @Override
    public ToolSearch.Response search(ToolSearch.Request request, String sessionId) {
      sessionIds.add(sessionId);
      return new ToolSearch.Response(tools);
    }

    @Override
    public Set<String> domains() {
      return Set.of("demo");
    }
  }

  // ---------------------------------------------------------------- Gateway

  /** 按 toolId 返回输出（或失败响应）；记录每次请求。 */
  public static final class FakeGateway implements ToolGatewayClient {
    private final Map<String, Function<ToolInvoke.Request, ToolInvoke.Response>> byTool =
        new HashMap<>();
    public final List<ToolInvoke.Request> calls = new ArrayList<>();

    public FakeGateway on(String toolId, Function<JsonNode, JsonNode> output) {
      byTool.put(
          toolId,
          req ->
              ToolInvoke.Response.succeeded(
                  req.executionContext().toolCallId(), 1, output.apply(req.arguments())));
      return this;
    }

    public FakeGateway fails(String toolId, ToolInvoke.ErrorCode code) {
      byTool.put(
          toolId,
          req -> ToolInvoke.Response.failed(req.executionContext().toolCallId(), 1, code, "fake"));
      return this;
    }

    @Override
    public ToolInvoke.Response invoke(ToolInvoke.Request request) {
      calls.add(request);
      Function<ToolInvoke.Request, ToolInvoke.Response> f = byTool.get(request.toolId());
      if (f == null) {
        return ToolInvoke.Response.failed(
            request.executionContext().toolCallId(),
            1,
            ToolInvoke.ErrorCode.TOOL_NOT_FOUND,
            "no fake for " + request.toolId());
      }
      return f.apply(request);
    }

    public List<String> calledToolIds() {
      return calls.stream().map(ToolInvoke.Request::toolId).toList();
    }
  }

  // ---------------------------------------------------------------- ScreenBuilder

  /**
   * 领域屏 Fake：结果屏 = Card 列出输出顶层键值（Table 输出则出 Table 供记忆断言）；确认屏 = Card + Form(reason) +
   * submit(confirm-close) + cancel。
   */
  public static final class FakeScreens implements ScreenBuilder {
    private final Set<String> resultIds;
    private final Set<String> confirmIds;

    public FakeScreens(Set<String> resultIds, Set<String> confirmIds) {
      this.resultIds = resultIds;
      this.confirmIds = confirmIds;
    }

    @Override
    public Set<String> resultToolIds() {
      return resultIds;
    }

    @Override
    public Set<String> confirmToolIds() {
      return confirmIds;
    }

    @Override
    public JsonNode result(String toolId, JsonNode output, ScreenContext ctx) {
      ObjectNode screen = UiNodes.screen("result-" + toolId.replace('.', '-'), "结果");
      if (output.has("items")) {
        ObjectNode props = UiNodes.component(screen, "list", "Table");
        ArrayNode cols = props.putArray("columns");
        cols.addObject().put("key", "itemId").put("title", "编号");
        ArrayNode rows = props.putArray("rows");
        for (JsonNode it : output.path("items")) {
          ObjectNode row = rows.addObject();
          row.put("id", it.path("itemId").asText());
          row.putObject("cells").put("itemId", it.path("itemId").asText());
        }
        return screen;
      }
      ObjectNode props = UiNodes.component(screen, "card", "Card");
      props.put("title", "结果");
      ArrayNode items = props.putArray("items");
      output
          .properties()
          .forEach(e -> UiNodes.labelValue(items, e.getKey(), e.getValue().asText()));
      return screen;
    }

    @Override
    public JsonNode confirmation(
        String toolId,
        Map<String, String> fixedArgs,
        Map<String, JsonNode> previousOutputs,
        String token,
        ScreenContext ctx) {
      ObjectNode screen = UiNodes.screen("confirm-" + toolId.replace('.', '-'), "请确认");
      ObjectNode card = UiNodes.component(screen, "summary", "Card");
      card.put("title", "即将执行");
      ArrayNode items = card.putArray("items");
      fixedArgs.forEach((k, v) -> UiNodes.labelValue(items, k, v));
      // 确认屏可读到前置步骤输出：把 status 展示出来供重校验比对
      JsonNode prev = previousOutputs.get("demo.item.get");
      if (prev != null) {
        UiNodes.labelValue(items, "status", prev.path("status").asText(""));
      }
      ObjectNode form = UiNodes.component(screen, "form", "Form");
      ObjectNode field = UiNodes.formField(form.putArray("fields"), "reason", "select", "原因", true);
      ArrayNode options = field.putArray("options");
      options.addObject().put("label", "损坏").put("value", "DAMAGED");
      UiNodes.submitAction(screen, "confirm-close", "确认", "danger", token);
      UiNodes.cancelAction(screen);
      return screen;
    }

    @Override
    public String summary(String toolId, JsonNode output) {
      return "ok";
    }
  }

  // ---------------------------------------------------------------- ConfirmationRecheck

  /** 重校验 Fake：recheck 调 demo.item.get；status=CLOSED 则拒绝；可信参数 amount 取自 recheck 输出。 */
  public static final class FakeRecheck implements ConfirmationRecheck {
    private final String toolId;
    private final String recheckToolId;

    public FakeRecheck(String toolId, String recheckToolId) {
      this.toolId = toolId;
      this.recheckToolId = recheckToolId;
    }

    @Override
    public String toolId() {
      return toolId;
    }

    @Override
    public String recheckToolId() {
      return recheckToolId;
    }

    @Override
    public Map<String, String> recheckArgs(Map<String, String> fixedArgs) {
      return Map.of("itemId", fixedArgs.getOrDefault("itemId", ""));
    }

    @Override
    public Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi) {
      return "CLOSED".equals(recheckOutput.path("status").asText())
          ? Optional.of("already closed")
          : Optional.empty();
    }

    @Override
    public Map<String, String> trustedArgs(JsonNode recheckOutput) {
      Map<String, String> m = new LinkedHashMap<>();
      if (recheckOutput.has("amount")) {
        m.put("amount", recheckOutput.path("amount").asText());
      }
      return m;
    }

    @Override
    public Set<String> trustedArgKeys() {
      return Set.of("amount");
    }
  }

  // ---------------------------------------------------------------- RunEventSink

  public static final class RecordingSink implements RunEventSink {
    public final List<SseEvent> events = new ArrayList<>();
    public int closed;

    /** 模拟客户端已断开：编排器据此在只读步骤前提前终止。emit 仍录制（断言用）。 */
    public volatile boolean gone;

    @Override
    public void emit(SseEvent event) {
      events.add(event);
    }

    @Override
    public boolean isClosed() {
      return gone;
    }

    @Override
    public void close() {
      closed++;
    }

    public List<String> names() {
      return events.stream().map(SseEvent::event).toList();
    }

    public JsonNode data(String eventName) {
      return events.stream()
          .filter(e -> e.event().equals(eventName))
          .map(SseEvent::data)
          .findFirst()
          .orElseThrow(() -> new AssertionError("no event " + eventName + " in " + names()));
    }

    public JsonNode lastData(String eventName) {
      List<SseEvent> hits = events.stream().filter(e -> e.event().equals(eventName)).toList();
      if (hits.isEmpty()) {
        throw new AssertionError("no event " + eventName + " in " + names());
      }
      return hits.get(hits.size() - 1).data();
    }
  }

  // ---------------------------------------------------------------- 可观察存储

  /** 记录每次 save 时的 Run 状态：用来钉住"落库那一刻"的状态，而不是事后看对象引用（内存版对象引用会掩盖顺序 bug）。 */
  public static final class ObservableRunRepository extends InMemoryRunRepository {
    public volatile Consumer<Run> onSave = r -> {};

    public ObservableRunRepository(Duration ttl, Clock clock) {
      super(ttl, clock);
    }

    @Override
    public void save(Run run) {
      onSave.accept(run);
      super.save(run);
    }
  }

  /** 可让下一次 put 抛异常：模拟共享存储（Redis）写记忆失败。 */
  public static final class ObservableConversationMemory extends InMemoryConversationMemory {
    public volatile boolean failNextPut;

    public ObservableConversationMemory(Duration ttl, Clock clock) {
      super(ttl, clock);
    }

    @Override
    public void put(String sessionId, String conversationId, Memory memory) {
      if (failNextPut) {
        failNextPut = false;
        throw new IllegalStateException("memory store unavailable (test)");
      }
      super.put(sessionId, conversationId, memory);
    }
  }
}
