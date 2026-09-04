package com.strato.spi;

import com.fasterxml.jackson.databind.JsonNode;

/** 领域服务实现的工具执行入口。只做确定性业务执行；参数 / 返回值 Schema 校验、鉴权、幂等、超时、审计全部由 Tool Gateway 负责。实现类不得自行重试。 */
public interface ToolHandler {
  String toolId();

  String version();

  JsonNode handle(JsonNode args, ExecutionContext ctx);
}
