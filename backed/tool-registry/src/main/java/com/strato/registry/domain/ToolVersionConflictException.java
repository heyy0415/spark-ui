package com.strato.registry.domain;

/** 同 toolId@version 重复注册。已发布版本不可变（contracts.md §5）。 */
public class ToolVersionConflictException extends DomainException {
  private static final long serialVersionUID = 1L;

  private final String key;

  public ToolVersionConflictException(String key) {
    super("tool version already registered: " + key);
    this.key = key;
  }

  public String key() {
    return key;
  }
}
