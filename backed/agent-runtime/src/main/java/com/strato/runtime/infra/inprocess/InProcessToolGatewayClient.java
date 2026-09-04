package com.strato.runtime.infra.inprocess;

import com.strato.contracts.model.ToolInvoke;
import com.strato.gateway.application.InvokeToolUseCase;
import com.strato.runtime.application.port.ToolGatewayClient;
import org.springframework.stereotype.Component;

/**
 * 进程内适配：直接调用 gateway 模块的 application 用例，Gateway 的全部管线（校验 / 鉴权 / 幂等 / 审计）照常执行。 失败以契约
 * tool-invoke.response（status=failed + error.code）形态返回，Runtime 按结构化 code 映射；本类不依赖 gateway 的 domain
 * / infra 包。HTTP 适配为后续 change（届时 4xx/5xx 的 error-response 同样映射为 failed Response）。
 */
@Component
public class InProcessToolGatewayClient implements ToolGatewayClient {
  private final InvokeToolUseCase invoke;

  public InProcessToolGatewayClient(InvokeToolUseCase invoke) {
    this.invoke = invoke;
  }

  @Override
  public ToolInvoke.Response invoke(ToolInvoke.Request request) {
    return invoke.executeToResponse(request);
  }
}
