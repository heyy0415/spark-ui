package com.strato.runtime.domain;

import java.util.Optional;

/** 令牌存储端口；consume 为原子"取出并删除"，保证一次性。 */
public interface ConfirmationTokenStore {
  void put(ConfirmationToken token);

  Optional<ConfirmationToken> consume(String token);
}
