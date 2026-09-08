package com.strato.gateway.api;

import com.strato.contracts.model.ToolInvoke;

/**
 * 执行面对外公开的调用接口（project-structure §2）。失败不抛异常，而是返回契约 tool-invoke.response{status=failed,
 * error.code}，调用方不依赖本模块 domain / infra 的任何类型。
 */
public interface ToolInvokePort {
  ToolInvoke.Response invoke(ToolInvoke.Request request);
}
