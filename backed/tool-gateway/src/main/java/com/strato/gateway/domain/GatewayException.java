package com.strato.gateway.domain;

import com.strato.contracts.model.ToolInvoke;

/** Gateway 执行链路中的失败；code 与 tool-invoke.response.error.code 一致。message 不含数据原文。 */
public class GatewayException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final ToolInvoke.ErrorCode code;

  public GatewayException(ToolInvoke.ErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public GatewayException(ToolInvoke.ErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public ToolInvoke.ErrorCode code() {
    return code;
  }
}
