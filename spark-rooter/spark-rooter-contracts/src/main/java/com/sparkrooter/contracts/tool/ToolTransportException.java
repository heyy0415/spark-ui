package com.sparkrooter.contracts.tool;

/**
 * 传输层失败。由 {@link ToolTransport} 实现抛出，Gateway 捕获后映射为契约 tool-invoke 的 error.code。
 *
 * <p>{@link Kind} 的意义不只是分类错误，更是回答「这次调用到底发生了没有」——Gateway 据此决定能否重试： 确定未到达工具（{@code NOT_FOUND} /
 * {@code UNREACHABLE}）可安全重试；结果未知（远端超时）不可重试， 因为远端可能已经执行成功，只是响应没赶上（跨进程重试会导致重复扣款）。
 *
 * <p>消息面向开发者，不直接展示给用户；远端的内部错误详情不得原样透出（避免泄漏 provider 实现细节）。
 */
public class ToolTransportException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /** 失败类别，决定 Gateway 的错误映射与重试判定。 */
  public enum Kind {
    /** 本进程 / 远端未注册该 toolId@version —— 确定未执行，可重试。 */
    NOT_FOUND,
    /** 连接不可达（DNS / 连接被拒 / 无坐标）—— 确定未到达工具，可重试。 */
    UNREACHABLE,
    /** 远端已收到但返回失败（非 2xx、响应体不合约）—— 已执行过，是否生效未知，不可重试。 */
    REMOTE_FAILED,
    /** 远端响应超时 —— <b>结果未知</b>，远端可能仍在执行或已成功，不可重试。 */
    REMOTE_TIMEOUT
  }

  private final Kind kind;

  public ToolTransportException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  public ToolTransportException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /**
   * 本次失败后重试是否安全。
   *
   * <p>只有「确定没碰到工具」才安全。这是跨进程与进程内最关键的语义差别：进程内超时可由 {@code Future.cancel(true)}
   * 真正中断，重试安全；跨进程超时只能中断本地等待，远端照常执行。
   */
  public boolean retryable() {
    return kind == Kind.NOT_FOUND || kind == Kind.UNREACHABLE;
  }
}
