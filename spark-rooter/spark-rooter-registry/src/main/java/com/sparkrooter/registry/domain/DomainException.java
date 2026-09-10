package com.sparkrooter.registry.domain;

/** 领域异常基类：业务规则被违反。由 web-mvc 的异常映射转成 HTTP 错误码。 */
public abstract class DomainException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  protected DomainException(String message) {
    super(message);
  }
}
