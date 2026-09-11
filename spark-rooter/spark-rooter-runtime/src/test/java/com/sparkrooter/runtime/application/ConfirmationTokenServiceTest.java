package com.sparkrooter.runtime.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.RunFailure;
import com.sparkrooter.runtime.infra.InMemoryConfirmationTokenStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 确认令牌（agent-safety §3）：一次性、绑定 run / action / 参数摘要 / 会话 / 宿主会话键 / 表单键白名单、TTL。 */
final class ConfirmationTokenServiceTest {

  private static final Instant BASE = Instant.parse("2026-09-11T00:00:00Z");
  private static final String RUN = "run_a";
  private static final String ACTION = "confirm-close";
  private static final String DIGEST = "d-1";
  private static final String CONV = "conv";
  private static final String SESS = "sess";
  private static final Set<String> KEYS = Set.of("reason");

  private final InMemoryConfirmationTokenStore store = new InMemoryConfirmationTokenStore();
  private final ConfirmationTokenService svc =
      new ConfirmationTokenService(store, Clock.fixed(BASE, ZoneOffset.UTC));

  @Test
  void issuedTokenIsOpaqueAndExpiresAfterDefaultTtl() {
    ConfirmationToken t = issue();
    assertThat(t.token()).startsWith("ct_").hasSizeGreaterThanOrEqualTo(16);
    assertThat(t.expiresAt()).isEqualTo(BASE.plus(ConfirmationTokenService.DEFAULT_TTL));
    assertThat(t.allowedFormKeys()).containsExactly("reason");
    assertThat(t.stepSeq()).isEqualTo(2);
  }

  @Test
  void twoTokensNeverCollide() {
    assertThat(issue().token()).isNotEqualTo(issue().token());
  }

  @Test
  void happyPathConsumesOnce() {
    ConfirmationToken t = issue();
    ConfirmationToken consumed =
        svc.consume(t.token(), RUN, ACTION, DIGEST, CONV, SESS, Map.of("reason", "DAMAGED"));
    assertThat(consumed.token()).isEqualTo(t.token());
    assertThat(store.consume(t.token())).isEmpty();
  }

  @Test
  void replayIsTokenUnknownNotPlainReject() {
    ConfirmationToken t = issue();
    svc.consume(t.token(), RUN, ACTION, DIGEST, CONV, SESS, Map.of());
    // TokenUnknown 是 RunFailure 子类，code 同为 CONFIRMATION_REJECTED，但调用方据类型区分「没消费任何令牌」
    assertThatThrownBy(() -> svc.consume(t.token(), RUN, ACTION, DIGEST, CONV, SESS, Map.of()))
        .isInstanceOf(ConfirmationTokenService.TokenUnknown.class)
        .satisfies(e -> assertThat(((RunFailure) e).code()).isEqualTo("CONFIRMATION_REJECTED"));
    assertThatThrownBy(() -> svc.consume("ct_forged", RUN, ACTION, DIGEST, CONV, SESS, Map.of()))
        .isInstanceOf(ConfirmationTokenService.TokenUnknown.class);
  }

  @Test
  void expiredTokenIsRejected() {
    ConfirmationToken t = issue();
    ConfirmationTokenService later =
        new ConfirmationTokenService(
            store, Clock.fixed(BASE.plus(Duration.ofMinutes(10)), ZoneOffset.UTC));
    assertRejected(
        () -> later.consume(t.token(), RUN, ACTION, DIGEST, CONV, SESS, Map.of()), "expired");
  }

  @Test
  void customTtlIsHonoured() {
    ConfirmationTokenService shortLived =
        new ConfirmationTokenService(
            store, Clock.fixed(BASE, ZoneOffset.UTC), Duration.ofSeconds(30));
    ConfirmationToken t = shortLived.issue(RUN, ACTION, 2, DIGEST, CONV, SESS, KEYS);
    assertThat(t.expiresAt()).isEqualTo(BASE.plusSeconds(30));
    assertThat(t.expired(BASE.plusSeconds(29))).isFalse();
    assertThat(t.expired(BASE.plusSeconds(30))).isTrue();
  }

  @Test
  void differentRunOrActionIsRejected() {
    assertRejected(
        () -> svc.consume(issue().token(), "run_b", ACTION, DIGEST, CONV, SESS, Map.of()),
        "different run/action");
    assertRejected(
        () -> svc.consume(issue().token(), RUN, "confirm-other", DIGEST, CONV, SESS, Map.of()),
        "different run/action");
  }

  @Test
  void changedArgumentsDigestIsRejected() {
    assertRejected(
        () -> svc.consume(issue().token(), RUN, ACTION, "d-2", CONV, SESS, Map.of()),
        "arguments changed");
  }

  @Test
  void differentConversationOrSessionIsRejected() {
    assertRejected(
        () -> svc.consume(issue().token(), RUN, ACTION, DIGEST, "other-conv", SESS, Map.of()),
        "conversation/session");
    // 知道会话号不等于能确认：sessionId 来自宿主 SessionIdResolver
    assertRejected(
        () -> svc.consume(issue().token(), RUN, ACTION, DIGEST, CONV, "other-sess", Map.of()),
        "conversation/session");
  }

  @Test
  void formDataKeyOutsideWhitelistIsRejected() {
    assertRejected(
        () ->
            svc.consume(
                issue().token(),
                RUN,
                ACTION,
                DIGEST,
                CONV,
                SESS,
                Map.of("reason", "x", "amount", "1.00")),
        "formData key not allowed");
  }

  @Test
  void rejectionConsumesTokenSoItCannotBeRetried() {
    ConfirmationToken t = issue();
    assertRejected(
        () -> svc.consume(t.token(), RUN, ACTION, "d-2", CONV, SESS, Map.of()),
        "arguments changed");
    assertThatThrownBy(() -> svc.consume(t.token(), RUN, ACTION, DIGEST, CONV, SESS, Map.of()))
        .isInstanceOf(ConfirmationTokenService.TokenUnknown.class);
  }

  private ConfirmationToken issue() {
    return svc.issue(RUN, ACTION, 2, DIGEST, CONV, SESS, KEYS);
  }

  private static void assertRejected(Runnable r, String internalReasonPart) {
    assertThatThrownBy(r::run)
        .isInstanceOf(RunFailure.class)
        .isNotInstanceOf(ConfirmationTokenService.TokenUnknown.class)
        .satisfies(e -> assertThat(((RunFailure) e).code()).isEqualTo("CONFIRMATION_REJECTED"))
        .hasMessageContaining(internalReasonPart);
  }
}
