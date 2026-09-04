package com.strato.runtime.application.port;

import com.strato.contracts.model.ToolInvoke;

/** 执行面调用端口；Runtime 执行任何工具的唯一途径（agent-safety §1）。 */
public interface ToolGatewayClient {
  ToolInvoke.Response invoke(ToolInvoke.Request request);
}
