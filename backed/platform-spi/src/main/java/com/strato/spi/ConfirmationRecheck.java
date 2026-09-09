package com.strato.spi;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;

/**
 * 需确认工具在用户确认后的重校验契约（agent-safety §3）：runtime 经 Gateway 重调 recheckToolId，再交本接口判定。 领域策略（可否退款 / 删除 /
 * 售后）留在领域模块，runtime 只做编排。
 */
public interface ConfirmationRecheck {

  /** 被确认的工具。 */
  String toolId();

  /** 确认后经 Gateway 重调的只读工具（版本由 runtime 从计划中的前置步骤取）。 */
  String recheckToolId();

  Map<String, String> recheckArgs(Map<String, String> fixedArgs);

  /** 非空 = 拒绝（内部原因，只进日志；对用户统一文案）。shownUi 为确认屏原文，供比对展示值。 */
  Optional<String> reject(JsonNode recheckOutput, JsonNode shownUi);

  /** 用可信来源覆盖 / 补齐的参数（如退款金额）；键不得与确认屏 Form 字段重叠。 */
  Map<String, String> trustedArgs(JsonNode recheckOutput);

  /** trustedArgs 可能产出的全部键（静态声明，供启动自检与 Form 字段互斥校验；不依赖某次输出）。 */
  java.util.Set<String> trustedArgKeys();
}
