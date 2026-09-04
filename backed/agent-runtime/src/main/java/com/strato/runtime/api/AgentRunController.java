package com.strato.runtime.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.ActionRequest;
import com.strato.contracts.model.IntentRequest;
import com.strato.contracts.model.RunSummary;
import com.strato.contracts.model.UiSchema;
import com.strato.runtime.application.RunOrchestrator;
import com.strato.runtime.domain.Run;
import com.strato.spi.Principal;
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
 * 前端 ↔ Runtime 三个端点。principal 只来自请求头（agent-safety §4：pageContext 不可信）。 Controller 只做绑定与分发；编排在
 * RunOrchestrator，在独立线程执行以便 SSE 立即返回。
 */
@RestController
@RequestMapping("/agent/runs")
public class AgentRunController {

  private static final Logger log = LoggerFactory.getLogger(AgentRunController.class);
  private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;

  private final RunOrchestrator orchestrator;
  private final SchemaValidator validator;
  private final ObjectMapper mapper;
  private final ExecutorService runExecutor;
  private final ScheduledExecutorService pingScheduler;

  public AgentRunController(
      RunOrchestrator orchestrator,
      SchemaValidator validator,
      ObjectMapper mapper,
      ExecutorService runExecutor,
      ScheduledExecutorService pingScheduler) {
    this.orchestrator = orchestrator;
    this.validator = validator;
    this.mapper = mapper;
    this.runExecutor = runExecutor;
    this.pingScheduler = pingScheduler;
  }

  @PostMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter start(
      @RequestBody JsonNode body,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
    Principal principal = principal(userId, tenantId);
    // 先按 intent-request 契约校验原始 JSON（additionalProperties / pattern / const），再绑定 record
    IntentRequest intent = validator.bind("intent-request", null, body, IntentRequest.class);
    log.info(
        "start_run, conversationId={} userId={} tenantId={}",
        intent.conversationId(),
        userId,
        tenantId);
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    SseRunEventSink sink = new SseRunEventSink(emitter, mapper, pingScheduler);
    runExecutor.submit(() -> orchestrator.start(intent, principal, traceId, sink));
    return emitter;
  }

  @PostMapping(path = "/{runId}/actions/{actionId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter confirm(
      @PathVariable String runId,
      @PathVariable String actionId,
      @RequestBody JsonNode body,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
      @RequestHeader(value = "X-User-Id", required = false) String userId,
      @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
    Principal principal = principal(userId, tenantId);
    ActionRequest action = validator.bind("action-request", null, body, ActionRequest.class);
    log.info("confirm_action, runId={} actionId={} userId={}", runId, actionId, userId);
    // runId 不存在必须同步 404，而不是在 SSE 里失败
    orchestrator.find(runId).orElseThrow(() -> new RunOrchestrator.RunNotFound(runId));
    SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
    SseRunEventSink sink = new SseRunEventSink(emitter, mapper, pingScheduler);
    Map<String, Object> formData = action.formData();
    runExecutor.submit(
        () ->
            orchestrator.confirm(
                runId, actionId, action.confirmationToken(), formData, principal, traceId, sink));
    return emitter;
  }

  @GetMapping("/{runId}")
  public RunSummary get(
      @PathVariable String runId,
      @RequestHeader(value = "X-Tenant-Id", required = false) String tenantId,
      @RequestHeader(value = "X-User-Id", required = false) String userId) {
    Principal principal = principal(userId, tenantId);
    Run run = orchestrator.find(runId).orElseThrow(() -> new RunOrchestrator.RunNotFound(runId));
    if (!run.principal().equals(principal)) {
      throw new RunOrchestrator.RunNotFound(runId); // 不泄露他人 Run 的存在
    }
    UiSchema ui = orchestrator.lastUi(runId).orElse(null);
    return new RunSummary(
        run.runId(),
        run.conversationId(),
        RunSummary.RunState.valueOf(run.state().name()),
        ui,
        run.failureCode().map(com.strato.contracts.model.RunFailureCode::valueOf).orElse(null),
        run.createdAt(),
        run.updatedAt());
  }

  private static Principal principal(String userId, String tenantId) {
    if (userId == null || userId.isBlank() || tenantId == null || tenantId.isBlank()) {
      throw new UnauthenticatedException();
    }
    return new Principal(userId, tenantId);
  }

  /** 缺 X-Tenant-Id / X-User-Id → 401。 */
  public static class UnauthenticatedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public UnauthenticatedException() {
      super("missing X-Tenant-Id or X-User-Id");
    }
  }
}
