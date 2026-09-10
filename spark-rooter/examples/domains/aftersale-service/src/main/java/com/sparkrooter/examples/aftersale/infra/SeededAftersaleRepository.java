package com.sparkrooter.examples.aftersale.infra;

import com.sparkrooter.examples.aftersale.domain.Aftersale;
import com.sparkrooter.examples.aftersale.domain.AftersaleRepository;
import com.sparkrooter.spi.SeedLoader;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * 从 data/aftersales.json 装配的内存仓储。种子行没有幂等键（历史单），以 "seed-<aftersaleId>" 占位； 运行期创建的单以 Gateway 透传的
 * idempotencyKey 为键，putIfAbsent 保证同键只写一次。
 */
@Repository
public class SeededAftersaleRepository implements AftersaleRepository {

  private static final Logger log = LoggerFactory.getLogger(SeededAftersaleRepository.class);

  /** aftersales.json 行。 */
  record Row(
      String aftersaleId,
      String orderId,
      String tenantId,
      String type,
      String status,
      String reason,
      Instant createdAt) {}

  private final Map<String, Aftersale> byIdemKey = new ConcurrentHashMap<>();

  public SeededAftersaleRepository() {
    List<Row> rows = SeedLoader.load("data/aftersales.json", Row.class);
    for (Row r : rows) {
      Aftersale a =
          new Aftersale(
              r.aftersaleId(),
              r.orderId(),
              r.tenantId(),
              Aftersale.Type.valueOf(r.type()),
              Aftersale.Status.valueOf(r.status()),
              r.reason(),
              r.createdAt());
      byIdemKey.put(key(a.tenantId(), "seed-" + a.aftersaleId()), a);
    }
    log.info("aftersale seed loaded aftersales={}", rows.size());
  }

  private static String key(String tenantId, String idem) {
    return tenantId + "/" + idem;
  }

  @Override
  public Optional<Aftersale> findByIdempotencyKey(String tenantId, String idempotencyKey) {
    return Optional.ofNullable(byIdemKey.get(key(tenantId, idempotencyKey)));
  }

  @Override
  public List<Aftersale> findByOrder(String tenantId, String orderId) {
    return byIdemKey.values().stream()
        .filter(a -> a.tenantId().equals(tenantId) && a.orderId().equals(orderId))
        .sorted(Comparator.comparing(Aftersale::createdAt))
        .toList();
  }

  @Override
  public List<Aftersale> findByTenant(String tenantId) {
    return byIdemKey.values().stream()
        .filter(a -> a.tenantId().equals(tenantId))
        .sorted(Comparator.comparing(Aftersale::createdAt).reversed())
        .toList();
  }

  @Override
  public Aftersale saveIfAbsent(String idempotencyKey, Aftersale aftersale) {
    Aftersale existing =
        byIdemKey.putIfAbsent(key(aftersale.tenantId(), idempotencyKey), aftersale);
    return existing != null ? existing : aftersale;
  }
}
