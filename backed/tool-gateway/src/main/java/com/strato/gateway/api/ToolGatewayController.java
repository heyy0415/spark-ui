package com.strato.gateway.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.strato.contracts.SchemaValidator;
import com.strato.contracts.model.ToolInvoke;
import com.strato.gateway.application.InvokeToolUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 执行面唯一端点。Controller 只做绑定与转发。 */
@RestController
@RequestMapping("/internal/tool-gateway")
public class ToolGatewayController {

  private static final Logger log = LoggerFactory.getLogger(ToolGatewayController.class);

  private final InvokeToolUseCase invoke;
  private final SchemaValidator validator;

  public ToolGatewayController(InvokeToolUseCase invoke, SchemaValidator validator) {
    this.invoke = invoke;
    this.validator = validator;
  }

  @PostMapping("/invoke")
  public ToolInvoke.Response invoke(@RequestBody JsonNode body) {
    ToolInvoke.Request req =
        validator.bind("tool-invoke", "#/$defs/request", body, ToolInvoke.Request.class);
    log.info(
        "invoke_tool, toolId={} version={} runId={} toolCallId={}",
        req.toolId(),
        req.toolVersion(),
        req.executionContext().runId(),
        req.executionContext().toolCallId());
    return invoke.execute(req);
  }
}
