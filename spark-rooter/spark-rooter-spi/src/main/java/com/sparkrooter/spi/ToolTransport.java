package com.sparkrooter.spi;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具调用传输端口（<b>预留</b>）：Gateway 寻址到工具后经它调用。本期只有进程内实现（直接调 ToolHandler）；HTTP / MCP 等远程传输为后续
 * change，接口签名先固定以免宿主二次适配。
 */
public interface ToolTransport {
  JsonNode invoke(ToolHandler handler, JsonNode args, ExecutionContext ctx);
}
