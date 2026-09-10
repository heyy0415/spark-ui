package com.sparkrooter.runtime.infra;

import com.sparkrooter.runtime.domain.ConfirmationToken;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/** 内存令牌表；consume = remove，天然一次性。 */
@Component
public class InMemoryConfirmationTokenStore implements ConfirmationTokenStore {
  private final Map<String, ConfirmationToken> store = new ConcurrentHashMap<>();

  @Override
  public void put(ConfirmationToken token) {
    store.put(token.token(), token);
  }

  @Override
  public Optional<ConfirmationToken> consume(String token) {
    return Optional.ofNullable(store.remove(token));
  }
}
