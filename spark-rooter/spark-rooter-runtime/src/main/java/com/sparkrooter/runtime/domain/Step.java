package com.sparkrooter.runtime.domain;

import java.util.Map;

/**
 * 计划中的一个工具步骤。fixedArgs 为规划时确定的参数（字符串键值，规范化后参与 argsDigest）； requiresConfirmation 为 true
 * 的步骤执行前必须经用户确认。
 */
public record Step(
    int seq,
    String toolId,
    String version,
    String displayName,
    Map<String, String> fixedArgs,
    boolean requiresConfirmation) {

  public Step {
    if (seq < 1) {
      throw new IllegalArgumentException("seq must start at 1");
    }
    fixedArgs = fixedArgs == null ? Map.of() : Map.copyOf(fixedArgs);
  }
}
