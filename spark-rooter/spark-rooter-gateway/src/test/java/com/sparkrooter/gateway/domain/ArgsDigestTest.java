package com.sparkrooter.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** 参数摘要：确定、键序敏感只由调用方规范化保证、长度固定 32 hex。 */
final class ArgsDigestTest {

  @Test
  void isDeterministicAnd32Hex() {
    String a = ArgsDigest.of("{\"itemId\":\"10001\"}");
    String b = ArgsDigest.of("{\"itemId\":\"10001\"}");
    assertThat(a).isEqualTo(b).hasSize(32).matches("^[0-9a-f]{32}$");
  }

  @Test
  void differentInputDifferentDigest() {
    assertThat(ArgsDigest.of("{\"itemId\":\"10001\"}"))
        .isNotEqualTo(ArgsDigest.of("{\"itemId\":\"10002\"}"));
  }

  @Test
  void emptyStringHasDigest() {
    assertThat(ArgsDigest.of("")).hasSize(32);
  }
}
