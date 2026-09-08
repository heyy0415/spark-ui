package com.strato.spi;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * 读取 classpath 上 data/*.json 种子（数组；键 = MySQL 列名 snake_case）为 record 列表。 自建 ObjectMapper（本模块无
 * Spring）：SNAKE_CASE 映射到 record 组件；金额 BigDecimal 来自字符串；时间 Instant 来自 ISO-8601。 未知键报错，防止 json 与
 * record 漂移。后续换 MySQL / Redis 只需替换仓储实现，不动本类。
 */
public final class SeedLoader {

  private static final ObjectMapper MAPPER =
      new ObjectMapper()
          .registerModule(new JavaTimeModule())
          .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
          .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, true);

  private SeedLoader() {}

  /** 读取并反序列化；资源缺失或结构不符抛异常（启动即失败）。 */
  public static <T> List<T> load(String resource, Class<T> type) {
    try (InputStream in = SeedLoader.class.getClassLoader().getResourceAsStream(resource)) {
      if (in == null) {
        throw new IllegalStateException("seed resource not found: " + resource);
      }
      return MAPPER.readValue(
          in, MAPPER.getTypeFactory().constructCollectionType(List.class, type));
    } catch (IOException e) {
      throw new UncheckedIOException("failed to read seed " + resource, e);
    }
  }
}
