package com.sparkrooter.webmvc.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.model.ActionRequest;
import com.sparkrooter.contracts.model.IntentRequest;
import com.sparkrooter.contracts.model.RunSummary;
import com.sparkrooter.contracts.model.UiSchema;
import com.sparkrooter.runtime.application.RunOrchestrator;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.SessionIdResolver;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 前端 ↔ Runtime 三个端点。请求体只有自然语言；身份不进内核：宿主 SessionIdResolver 在请求线程把请求映射为 sessionId（Run 隔离键）， 宿主
 * RunContextPropagator 把宿主 ThreadLocal 带到 agent-run-* 线程。Controller 只做绑定与分发；编排在 RunOrchestrator。
 */
@RestController
@RequestMapping("${spark.web.base-path:/agent}/runs")
public class AgentRunController {

  private static final Logger log = LoggerFactory.getLogger(AgentRunController.class);

  private final RunOrchestrator orchestrator;
  private final SchemaValidator validator;
  private final ObjectMapper mapper;
  private final ExecutorService runExecutor;
  private final ScheduledExecutorService pingScheduler;
  private final SessionIdResolver sessions;
  private final RunContextPropagator propagator;
  private final long sseTimeoutMs;

  public AgentRunController(
      RunOrchestrator orchestrator,
      SchemaValidator validator,
      ObjectMapper mapper,
      ExecutorService runExecutor,
      ScheduledExecutorService pingScheduler,
      SessionIdResolver sessions,
      RunContextPropagator propagator,
      long sseTimeoutMs) {
    this.orchestrator = orchestrator;
    this.validator = validator;
    this.mapper = mapper;
    this.runExecutor = runExecutor;
    this.pingScheduler = pingScheduler;
    this.sessions = sessions;
    this.propagator = propagator;
    this.sseTimeoutMs = sseTimeoutMs;
  }

  @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter start(
      @RequestBody JsonNode body,
      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
    // 先按 intent-request 契约校验原始 JSON（additionalProperties / pattern / const），再绑定 record
    IntentRequest intent = validator.bind("intent-request", null, body, IntentRequest.class);
    String sessionId = sessions.resolve(intent.conversationId());
    log.info("start_run, conversationId={}", intent.conversationId());
    SseEmitter emitter = new SseEmitter(sseTimeoutMs);
    SseRunEventSink sink = new SseRunEventSink(emitter, mapper, pingScheduler);
    submit(() -> orchestrator.start(intent, sessionId, traceId, sink));
    return emitter;
  }

  @PostMapping(path = "/{runId}/actions/{actionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter confirm(
      @PathVariable String runId,
      @PathVariable String actionId,
      @RequestBody JsonNode body,
      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
    ActionRequest action = validator.bind("action-request", null, body, ActionRequest.class);
    log.info("confirm_action, runId={} actionId={}", runId, actionId);
    // runId 不存在必须同步 404，而不是在 SSE 里失败
    Run run = orchestrator.find(runId).orElseThrow(() -> new RunOrchestrator.RunNotFound(runId));
    String sessionId = sessions.resolve(run.conversationId());
    SseEmitter emitter = new SseEmitter(sseTimeoutMs);
    SseRunEventSink sink = new SseRunEventSink(emitter, mapper, pingScheduler);
    Map<String, Object> formData = action.formData();
    submit(
        () ->
            orchestrator.confirm(
                runId, actionId, action.confirmationToken(), formData, sessionId, traceId, sink));
    return emitter;
  }

  @GetMapping("/{runId}")
  public RunSummary get(@PathVariable String runId) {
    Run run = orchestrator.find(runId).orElseThrow(() -> new RunOrchestrator.RunNotFound(runId));
    if (!run.sessionId().equals(sessions.resolve(run.conversationId()))) {
      throw new RunOrchestrator.RunNotFound(runId); // 不泄露他人 Run 的存在
    }
    UiSchema ui = orchestrator.lastUi(runId).orElse(null);
    return new RunSummary(
        run.runId(),
        run.conversationId(),
        RunSummary.RunState.valueOf(run.state().name()),
        ui,
        run.failureCode().map(com.sparkrooter.contracts.model.RunFailureCode::valueOf).orElse(null),
        run.createdAt(),
        run.updatedAt());
  }

  /** 切到 agent-run-* 线程前捕获宿主上下文，工作线程 restore / clear（finally）。 */
  private void submit(Runnable task) {
    Object hostCtx = propagator.capture();
    runExecutor.submit(
        () -> {
          propagator.restore(hostCtx);
          try {
            task.run();
          } finally {
            propagator.clear();
          }
        });
  }
}
