package com.sparkrooter.gateway.domain;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 单会话在飞工具调用数上限。
 *
 * <p><b>防的是只读查询洪水</b>：写操作（{@code idempotency=required}）已被 Gateway 的幂等 {@code claimOrAwait} 序列化——同
 * {@code (sessionId, idempotencyKey)} 的第二个调用者会等待或重放， 不会并发执行。但只读工具（示例领域里
 * 11/14）没有任何序列化，单个会话可以用并发请求占满整个 工具池，让其他用户全部被 {@code AbortPolicy} 拒绝。
 *
 * <p>与线程池有界的关系：池子防的是**全局**过载，本类防的是**单会话**挤占。二者互补—— 没有本类，一个用户就能拖垮所有人。
 *
 * <p>超限直接拒绝而不排队：排队只是把拒绝延后，同时还占着一个 HTTP 连接与一份内存。
 *
 * <p><b>名额衡量的是「在飞请求数」，不是「在跑的工具数」</b>（阶段 4 评审 F-1 明确）。同一 {@code idempotencyKey} 的并发调用里，只有 owner
 * 在真执行，其余在 Gateway 的 {@code claimOrAwait} 里等待别人的结果（最长 {@code execution.timeoutMs}）——它们同样占名额。
 *
 * <p>这是刻意的：等待并非免费，它占着一个 Gateway 线程与一个 HTTP 连接。而且「同一会话对同一 key
 * 并发提交」本身是异常模式（通常是前端重复提交），把它计入名额并在超限时拒绝，是合理行为 而非误伤。若要把两者分开统计，需要多一个配置面与一套计数器，当前无证据表明必要。
 *
 * <p>本类在 {@code domain/} 包内，不依赖 Spring（后端红线）。
 */
public final class SessionConcurrencyLimiter {

  private final int maxPerSession;
  private final Map<String, AtomicInteger> inFlight = new ConcurrentHashMap<>();

  /**
   * @param maxPerSession 单会话在飞上限；≤ 0 表示不限制（宿主显式关闭）
   */
  public SessionConcurrencyLimiter(int maxPerSession) {
    this.maxPerSession = maxPerSession;
  }

  /** 已获得的名额，必须在 finally 里 {@link Lease#close()}。 */
  public interface Lease extends AutoCloseable {
    @Override
    void close();
  }

  /** 不限制时的空名额；避免调用方写 null 判断。 */
  private static final Lease NO_OP = () -> {};

  /**
   * 申请一个名额。
   *
   * @param sessionId 宿主会话键
   * @return 名额；用 try-with-resources 或 try/finally 释放
   * @throws SessionBusyException 该会话在飞数已达上限
   */
  public Lease acquire(String sessionId) {
    if (maxPerSession <= 0 || sessionId == null) {
      return NO_OP;
    }
    AtomicInteger counter = inFlight.computeIfAbsent(sessionId, k -> new AtomicInteger());
    // CAS 循环而非 incrementAndGet 后回退：后者在超限瞬间会让计数短暂超过上限，
    // 并发压测时可能让另一个线程误判为"还有名额"
    while (true) {
      int current = counter.get();
      if (current >= maxPerSession) {
        throw new SessionBusyException(sessionId, current, maxPerSession);
      }
      if (counter.compareAndSet(current, current + 1)) {
        break;
      }
    }
    return () -> release(sessionId, counter);
  }

  /**
   * 释放名额；归零时移除 map 项。
   *
   * <p>不移除会让 map 随历史会话数无界增长——「加了限流又引入新泄漏」。用 {@code remove(key, value)} 的两参版本：只有当前值仍是同一个计数器实例时才删，
   * 避免删掉别的线程刚 {@code computeIfAbsent} 出来的新计数器。
   */
  private void release(String sessionId, AtomicInteger counter) {
    if (counter.decrementAndGet() <= 0) {
      inFlight.remove(sessionId, counter);
    }
  }

  /** 当前被跟踪的会话数，供自检与测试（正常应随会话结束回落）。 */
  public int trackedSessions() {
    return inFlight.size();
  }

  /** 某会话当前在飞数，供测试。 */
  public int inFlight(String sessionId) {
    AtomicInteger c = inFlight.get(sessionId);
    return c == null ? 0 : c.get();
  }

  /** 超限。由 Gateway 映射为契约 {@code RATE_LIMITED}（HTTP 429）。 */
  public static final class SessionBusyException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final transient String sessionId;

    SessionBusyException(String sessionId, int current, int max) {
      super("session has too many in-flight tool calls: " + current + " >= " + max);
      this.sessionId = sessionId;
    }

    public String sessionId() {
      return sessionId;
    }
  }
}
