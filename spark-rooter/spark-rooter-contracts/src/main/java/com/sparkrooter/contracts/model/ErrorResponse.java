package com.sparkrooter.contracts.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** 契约 error-response：所有 4xx / 5xx 的统一响应体。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
    @NotBlank String code,
    @NotBlank String message,
    @NotBlank String traceId,
    List<Detail> details) {

  /** 字段级错误明细。 */
  public record Detail(String path, String message) {}

  /** HTTP 错误码枚举（与 error-response.schema.json 的 code enum 一致）。 */
  public enum Code {
    REQUEST_INVALID,
    UNAUTHENTICATED,
    FORBIDDEN,
    NOT_FOUND,
    TOOL_VERSION_CONFLICT,
    INTERNAL_ERROR,
    /** 过载拒绝（HTTP 429）。与 FORBIDDEN 分开：前者退避重试有意义，后者无意义。 */
    RATE_LIMITED
  }

  public static ErrorResponse of(Code code, String message, String traceId) {
    return new ErrorResponse(code.name(), message, traceId, null);
  }
}
