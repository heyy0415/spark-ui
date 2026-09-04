package com.strato.gateway.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** 参数摘要：SHA-256(规范化 JSON 文本) 前 16 字节 hex。审计只记摘要，不记参数原文（agent-safety §5 / §6）。 */
public final class ArgsDigest {

  private ArgsDigest() {}

  public static String of(String canonicalJson) {
    try {
      byte[] h =
          MessageDigest.getInstance("SHA-256")
              .digest(canonicalJson.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(h, 0, 16);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
