package com.strato.runtime.domain;

/** Run 级失败：携带 run-failed 契约的 code；userText 可选，有则覆盖按 code 映射的默认用户文案（内部 message 只进日志）。 */
public class RunFailure extends RuntimeException {
  private static final long serialVersionUID = 1L;

  private final String code;
  private final String userText;

  public RunFailure(String code, String message) {
    this(code, message, (Throwable) null);
  }

  public RunFailure(String code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
    this.userText = null;
  }

  private RunFailure(String code, String message, String userText) {
    super(message);
    this.code = code;
    this.userText = userText;
  }

  /** 带专用用户文案的失败（如确认后领域策略拒绝：code 仍是 CONFIRMATION_REJECTED，文案区分于令牌拒绝）。 */
  public static RunFailure withUserText(String code, String message, String userText) {
    return new RunFailure(code, message, userText);
  }

  public String code() {
    return code;
  }

  /** null 表示使用默认映射文案。 */
  public String userText() {
    return userText;
  }
}
