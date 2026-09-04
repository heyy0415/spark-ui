package com.strato.spi;

/** 调用方身份，来自请求头，不信任请求体。 */
public record Principal(String userId, String tenantId) {
  public Principal {
    if (userId == null || userId.isBlank()) throw new IllegalArgumentException("userId is blank");
    if (tenantId == null || tenantId.isBlank())
      throw new IllegalArgumentException("tenantId is blank");
  }
}
