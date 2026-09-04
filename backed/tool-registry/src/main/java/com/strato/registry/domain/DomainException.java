package com.strato.registry.domain;

/** 领域异常基类：业务规则被违反。由 api 层的 @RestControllerAdvice 映射为 HTTP 错误码。 */
public abstract class DomainException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  protected DomainException(String message) {
    super(message);
  }
}
