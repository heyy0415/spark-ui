package com.sparkrooter.runtime.infra.selfcheck;

import com.sparkrooter.runtime.application.ConfirmationTokenService;
import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.spi.SelfCheck;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** 令牌自检：过期、重放、参数摘要不符、白名单外键、sessionId 不一致 五种情况必须全部被拒；正常路径必须通过。 */
public class TokenSelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(TokenSelfCheck.class);

  private static final String CONV = "conv_sc";
  private static final String SESS = "sess_sc";

  private final ConfirmationTokenStore store;

  public TokenSelfCheck(ConfirmationTokenStore store) {
    this.store = store;
  }

  @Override
  public String name() {
    return "confirmation token";
  }

  @Override
  public void run() {
    Instant base = Instant.parse("2026-09-04T00:00:00Z");
    ConfirmationTokenService svc =
        new ConfirmationTokenService(store, Clock.fixed(base, ZoneOffset.UTC));
    Set<String> keys = Set.of("reason");

    // 正常
    ConfirmationToken ok = svc.issue("run_sc", "confirm-refund", 3, "digest-a", CONV, SESS, keys);
    svc.consume(
        ok.token(),
        "run_sc",
        "confirm-refund",
        "digest-a",
        CONV,
        SESS,
        Map.of("reason", "DAMAGED"));

    // 重放
    expectReject(
        () -> svc.consume(ok.token(), "run_sc", "confirm-refund", "digest-a", CONV, SESS, Map.of()),
        "replayed");

    // 过期
    ConfirmationToken exp = svc.issue("run_sc", "confirm-refund", 3, "digest-a", CONV, SESS, keys);
    ConfirmationTokenService later =
        new ConfirmationTokenService(
            store, Clock.fixed(base.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
    expectReject(
        () ->
            later.consume(
                exp.token(), "run_sc", "confirm-refund", "digest-a", CONV, SESS, Map.of()),
        "expired");

    // 摘要不符
    ConfirmationToken dig = svc.issue("run_sc", "confirm-refund", 3, "digest-a", CONV, SESS, keys);
    expectReject(
        () ->
            svc.consume(dig.token(), "run_sc", "confirm-refund", "digest-b", CONV, SESS, Map.of()),
        "digest-mismatch");

    // 白名单外键（如前端试图注入 amount）
    ConfirmationToken extra =
        svc.issue("run_sc", "confirm-refund", 3, "digest-a", CONV, SESS, keys);
    expectReject(
        () ->
            svc.consume(
                extra.token(),
                "run_sc",
                "confirm-refund",
                "digest-a",
                CONV,
                SESS,
                Map.of("reason", "DAMAGED", "amount", "1.00")),
        "extra-key");

    // sessionId 不一致（评审 M-2：知道会话号不等于能确认他人的高风险操作）
    ConfirmationToken sess = svc.issue("run_sc", "confirm-refund", 3, "digest-a", CONV, SESS, keys);
    expectReject(
        () ->
            svc.consume(
                sess.token(),
                "run_sc",
                "confirm-refund",
                "digest-a",
                CONV,
                "other-session",
                Map.of()),
        "session-mismatch");

    log.info(
        "selfcheck: token expired/replayed/digest-mismatch/extra-key/session-mismatch rejected OK");
  }

  private static void expectReject(Runnable r, String what) {
    try {
      r.run();
    } catch (RunFailure e) {
      if ("CONFIRMATION_REJECTED".equals(e.code())) {
        return;
      }
      throw new IllegalStateException(
          "token check '" + what + "' rejected with wrong code " + e.code());
    }
    throw new IllegalStateException("token check '" + what + "' was accepted but must be rejected");
  }
}
