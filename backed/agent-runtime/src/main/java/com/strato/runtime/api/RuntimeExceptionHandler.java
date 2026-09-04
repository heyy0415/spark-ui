package com.strato.runtime.api;

import com.strato.contracts.ContractViolationException;
import com.strato.contracts.model.ErrorResponse;
import com.strato.runtime.application.RunOrchestrator;
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

/** Runtime 端点错误映射（spec §4.2 HTTP 表）。 */
@RestControllerAdvice(basePackageClasses = AgentRunController.class)
public class RuntimeExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(RuntimeExceptionHandler.class);

  @ExceptionHandler(AgentRunController.UnauthenticatedException.class)
  public ResponseEntity<ErrorResponse> unauthenticated(
      AgentRunController.UnauthenticatedException e) {
    return build(HttpStatus.UNAUTHORIZED, ErrorResponse.Code.UNAUTHENTICATED, e.getMessage(), null);
  }

  @ExceptionHandler(RunOrchestrator.RunNotFound.class)
  public ResponseEntity<ErrorResponse> notFound(RunOrchestrator.RunNotFound e) {
    return build(HttpStatus.NOT_FOUND, ErrorResponse.Code.NOT_FOUND, e.getMessage(), null);
  }

  @ExceptionHandler(ContractViolationException.class)
  public ResponseEntity<ErrorResponse> contract(ContractViolationException e) {
    List<ErrorResponse.Detail> details =
        e.violations().stream()
            .map(v -> new ErrorResponse.Detail(v.getInstanceLocation().toString(), v.getMessage()))
            .toList();
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorResponse.Code.REQUEST_INVALID,
        "request does not match contract " + e.contractName(),
        details);
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
    return build(
        HttpStatus.BAD_REQUEST,
        ErrorResponse.Code.REQUEST_INVALID,
        "request body invalid",
        details);
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
    log.error("runtime_unhandled traceId={}", traceId, e);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(ErrorResponse.of(ErrorResponse.Code.INTERNAL_ERROR, "internal error", traceId));
  }

  private static ResponseEntity<ErrorResponse> build(
      HttpStatus status,
      ErrorResponse.Code code,
      String message,
      List<ErrorResponse.Detail> details) {
    return ResponseEntity.status(status)
        .body(
            new ErrorResponse(
                code.name(),
                message,
                traceId(),
                details == null || details.isEmpty() ? null : details));
  }

  private static String traceId() {
    String t = MDC.get("traceId");
    return t != null
        ? t
        : "trace_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
  }
}
