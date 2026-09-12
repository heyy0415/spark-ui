package com.sparkrooter.contracts.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.spi.ExecutionContext;

/**
 * 工具调用传输端口：Gateway 完成治理（输入校验 / 访问策略 / 幂等）后，经它把参数送到工具并取回结果。
 *
 * <p><b>只负责传输</b>。输入 / 输出 Schema 校验、访问策略、幂等、超时、重试、脱敏、审计全部留在 Gateway 的 {@code InvokeToolUseCase}
 * ——否则远程工具会绕过执行面治理。实现不得自行重试（重试策略按 Manifest 由 Gateway 统一决定）。
 *
 * <p>按 <b>Manifest</b> 而非 ToolHandler 寻址：{@code protocol=in-process} 的工具在本进程有 handler 实例， 而 {@code
 * protocol=http} 的工具在另一个进程，hub 里根本没有它的 handler。只有传 Manifest 才能同时表达两种传输。
 *
 * <p>放在 contracts 而非 spi：本接口签名需要 {@link ToolManifest}，而 spi 是最底层包，不得依赖任何 com.sparkrooter
 * artifact（check-module-deps 守护）。contracts 已依赖 spi，是能同时看到两者的最低层级。
 *
 * <p>实现按 {@link ToolManifest#protocol()} 选择：
 *
 * <ul>
 *   <li>{@code in-process} → 查本进程已注册的 handler，反射调用
 *   <li>{@code http} → 读 {@code manifest.provider()} 坐标，POST 到远端 provider
 * </ul>
 */
public interface ToolTransport {

  /** 本实现支持的协议；Gateway 据此分派。 */
  ToolManifest.Protocol protocol();

  /**
   * 调用工具。
   *
   * @param manifest 已注册的 Manifest，含寻址所需的全部信息
   * @param args 已通过 inputSchema 校验的参数
   * @param ctx 执行上下文；{@code idempotencyKey} 必须透传给远端，供其去重
   * @return 工具原始输出（未校验、未脱敏，由 Gateway 后续处理）
   * @throws ToolTransportException 传输层失败（找不到工具 / 连接失败 / 远端非 2xx）
   */
  JsonNode invoke(ToolManifest manifest, JsonNode args, ExecutionContext ctx);
}
