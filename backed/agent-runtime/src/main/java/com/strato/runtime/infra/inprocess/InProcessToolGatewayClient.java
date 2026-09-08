package com.strato.runtime.infra.inprocess;

import com.strato.contracts.model.ToolInvoke;
import com.strato.gateway.api.ToolInvokePort;
import com.strato.runtime.application.port.ToolGatewayClient;
import org.springframework.stereotype.Component;

/**
 * 进程内适配：只依赖 gateway 的 api 包接口（project-structure §2）。Gateway 的全部管线（校验 / 鉴权 / 幂等 / 审计）照常执行， 失败以契约
 * tool-invoke.response{status=failed} 返回。HTTP 适配为后续 change。
 */
@Component
public class InProcessToolGatewayClient implements ToolGatewayClient {
  private final ToolInvokePort gateway;

  public InProcessToolGatewayClient(ToolInvokePort gateway) {
    this.gateway = gateway;
  }

  @Override
  public ToolInvoke.Response invoke(ToolInvoke.Request request) {
    return gateway.invoke(request);
  }
}
