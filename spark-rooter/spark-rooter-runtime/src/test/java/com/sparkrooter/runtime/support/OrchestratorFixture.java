package com.sparkrooter.runtime.support;

import static com.sparkrooter.runtime.support.TestFixtures.ENTITY;
import static com.sparkrooter.runtime.support.TestFixtures.VALIDATOR;
import static com.sparkrooter.runtime.support.TestFixtures.confirmSchema;
import static com.sparkrooter.runtime.support.TestFixtures.entityParam;
import static com.sparkrooter.runtime.support.TestFixtures.idSchema;
import static com.sparkrooter.runtime.support.TestFixtures.listSchema;
import static com.sparkrooter.runtime.support.TestFixtures.meta;
import static com.sparkrooter.runtime.support.TestFixtures.names;
import static com.sparkrooter.runtime.support.TestFixtures.needsConfirm;
import static com.sparkrooter.runtime.support.TestFixtures.plainParam;
import static com.sparkrooter.runtime.support.TestFixtures.readOnly;
import static com.sparkrooter.runtime.support.TestFixtures.registry;

import com.sparkrooter.contracts.model.IntentRequest;
import com.sparkrooter.contracts.model.ToolSearch;
import com.sparkrooter.runtime.application.ConfirmationTokenService;
import com.sparkrooter.runtime.application.RecheckRegistry;
import com.sparkrooter.runtime.application.RunOrchestrator;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.runtime.application.screen.ScreenRegistry;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.Step;
import com.sparkrooter.runtime.infra.InMemoryConfirmationTokenStore;
import com.sparkrooter.runtime.infra.InMemoryConversationMemory;
import com.sparkrooter.runtime.infra.InMemoryRunRepository;
import com.sparkrooter.spi.ConfirmationRecheck;
import com.sparkrooter.spi.ScreenBuilder;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 装配 RunOrchestrator 的 12 个协作者，固定时钟。三个中性工具：demo.item.list（只读、澄清源）、demo.item.get（只读、实体参数）、
 * demo.item.close（需确认，前置 get，可信参数 amount）。
 */
public final class OrchestratorFixture {

  public static final String LIST = "demo.item.list";
  public static final String GET = "demo.item.get";
  public static final String CLOSE = "demo.item.close";
  public static final String CONV = "conv-1";
  public static final String SESSION = "sess-1";
  public static final String TRACE = "trace-1";
  public static final Instant NOW = Instant.parse("2026-09-11T00:00:00Z");

  public final Fakes.FakeLlm llm = new Fakes.FakeLlm();
  public final Fakes.FakeGateway gateway = new Fakes.FakeGateway();
  public final Fakes.FakeRegistry registryClient;
  public final InMemoryRunRepository runs;
  public final InMemoryConversationMemory memory;
  public final InMemoryConfirmationTokenStore tokenStore = new InMemoryConfirmationTokenStore();
  public final ToolMetaRegistry meta;
  public final RunOrchestrator orchestrator;

  public final List<ToolSearch.ToolCandidate> candidates =
      List.of(
          readOnly(LIST, listSchema()),
          readOnly(GET, idSchema()),
          needsConfirm(CLOSE, confirmSchema()));

  /** 默认装配：领域屏覆盖三个工具、重校验覆盖 close。 */
  public OrchestratorFixture() {
    this(
        List.of(new Fakes.FakeScreens(Set.of(LIST, GET, CLOSE), Set.of(CLOSE))),
        List.of(new Fakes.FakeRecheck(CLOSE, GET)),
        true);
  }

  public OrchestratorFixture(
      List<ScreenBuilder> screens, List<ConfirmationRecheck> rechecks, boolean withCandidates) {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    this.registryClient = new Fakes.FakeRegistry(withCandidates ? candidates : List.of());
    this.runs = new InMemoryRunRepository(Duration.ofHours(1), clock);
    this.memory = new InMemoryConversationMemory(Duration.ofMinutes(30), clock);
    this.meta =
        registry(
            meta(
                LIST,
                List.of(),
                ENTITY,
                plainParam("status", null, false),
                plainParam("limit", "20", true)),
            meta(GET, List.of(), null, entityParam("itemId", "^\\d{5}$")),
            meta(
                CLOSE,
                List.of(GET),
                null,
                entityParam("itemId", "^\\d{5}$"),
                plainParam("amount", null, false)));
    this.orchestrator =
        new RunOrchestrator(
            runs,
            registryClient,
            gateway,
            llm,
            new ScreenRegistry(screens, VALIDATOR),
            new RecheckRegistry(rechecks),
            names(LIST, "列出条目", GET, "查看条目", CLOSE, "关闭条目"),
            new ConfirmationTokenService(tokenStore, clock),
            meta,
            memory,
            VALIDATOR,
            clock);
  }

  public static IntentRequest intent(String message) {
    return new IntentRequest(
        CONV,
        message,
        new IntentRequest.ClientCapabilities("1.0", List.of("Card", "Table", "Form")));
  }

  public static Plan plan(Step... steps) {
    return new Plan("demo", List.of(steps));
  }

  public static Step step(int seq, String toolId, Map<String, String> args, boolean confirm) {
    return new Step(seq, toolId, "1.0.0", toolId, args, confirm);
  }

  public String start(String message, Fakes.RecordingSink sink) {
    return orchestrator.start(intent(message), SESSION, TRACE, sink);
  }
}
