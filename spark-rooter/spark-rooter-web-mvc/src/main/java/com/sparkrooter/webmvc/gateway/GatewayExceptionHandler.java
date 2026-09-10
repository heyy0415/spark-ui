package com.sparkrooter.webmvc.gateway;

import com.sparkrooter.contracts.ContractViolationException;
import com.sparkrooter.contracts.model.ErrorResponse;
import com.sparkrooter.gateway.domain.GatewayException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Gateway 错误映射。GatewayException 按 code 映射 HTTP 状态：
 * INPUT_INVALID→400、FORBIDDEN→403、TOOL_NOT_FOUND→404、其余（TIMEOUT / HANDLER_ERROR / OUTPUT_INVALID）→
 * 502 表示下游工具失败。
 */
@RestControllerAdvice(basePackageClasses = ToolGatewayController.class)
public class GatewayExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GatewayExceptionHandler.class);

  @ExceptionHandler(GatewayException.class)
  public ResponseEntity<ErrorResponse> gateway(GatewayException e) {
    return switch (e.code()) {
      case INPUT_INVALID ->
          build(HttpStatus.BAD_REQUEST, ErrorResponse.Code.REQUEST_INVALID, e.getMessage());
      case FORBIDDEN -> build(HttpStatus.FORBIDDEN, ErrorResponse.Code.FORBIDDEN, e.getMessage());
      case TOOL_NOT_FOUND ->
          build(HttpStatus.NOT_FOUND, ErrorResponse.Code.NOT_FOUND, e.getMessage());
      case TIMEOUT, HANDLER_ERROR, OUTPUT_INVALID ->
          build(HttpStatus.BAD_GATEWAY, ErrorResponse.Code.INTERNAL_ERROR, e.getMessage());
    };
  }

  @ExceptionHandler(ContractViolationException.class)
  public ResponseEntity<ErrorResponse> contract(ContractViolationException e) {
    List<ErrorResponse.Detail> details =
        e.violations().stream()
            .map(v -> new ErrorResponse.Detail(v.getInstanceLocation().toString(), v.getMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                ErrorResponse.Code.REQUEST_INVALID.name(),
                "request does not match contract " + e.contractName(),
                traceId(),
                details.isEmpty() ? null : details));
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<ErrorResponse> beanValidation(MethodArgumentNotValidException e) {
    List<ErrorResponse.Detail> details =
        e.getBindingResult().getFieldErrors().stream()
            .map(
                f ->
                    new ErrorResponse.Detail(
                        "/" + f.getField().replace('.', '/'), f.getDefaultMessage()))
            .toList();
    return ResponseEntity.badRequest()
        .body(
            new ErrorResponse(
                ErrorResponse.Code.REQUEST_INVALID.name(),
                "request body invalid",
                traceId(),
                details));
  }

  /** 非法 JSON / 空请求体 → 400（而非 500）。 */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> unreadable(HttpMessageNotReadableException e) {
    return ResponseEntity.badRequest()
        .body(
            ErrorResponse.of(
                ErrorResponse.Code.REQUEST_INVALID, "request body unreadable", traceId()));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> internal(Exception e) {
    String traceId = traceId();
    log.error("gateway_unhandled traceId={}", traceId, e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of(ErrorResponse.Code.INTERNAL_ERROR, "internal error", traceId));
  }

  private static ResponseEntity<ErrorResponse> build(
      HttpStatus status, ErrorResponse.Code code, String msg) {
    return ResponseEntity.status(status).body(ErrorResponse.of(code, msg, traceId()));
  }

  private static String traceId() {
    String t = MDC.get("traceId");
    return t != null
        ? t
        : "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
