package com.strato.runtime.domain;

/** Run 级失败：携带 run-failed 契约的 code。 */
public class RunFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;

  public RunFailure(String code, String message) {
    super(message);
    this.code = code;
  }

  public RunFailure(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public String code() {
    return code;
  }
}
