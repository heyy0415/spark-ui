package com.sparkrooter.redis;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.gateway.domain.IdempotencyStore;
import com.sparkrooter.runtime.domain.ConfirmationTokenStore;
import com.sparkrooter.runtime.domain.RunRepository;
import com.sparkrooter.spi.ConversationMemory;
import com.sparkrooter.starter.SparkRooterAutoConfiguration;
import com.sparkrooter.starter.SparkRooterProperties;
import java.time.Clock;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 四个存储端口的 Redis 装配，只在 {@code spark.storage.type=redis} 时生效。
 *
 * <p>顺序很要紧：必须排在 {@link SparkRooterAutoConfiguration} <b>之前</b>——starter 里四个默认实现都是
 * {@code @ConditionalOnMissingBean}，本类的 Bean 定义先注册，starter 才会让位；排在 {@link RedisAutoConfiguration}
 * <b>之后</b>，才能拿到宿主的 {@link StringRedisTemplate}（连接配置沿用 Spring Boot 的 {@code spring.data.redis.*}）。
 *
 * <p>四个 Bean 各自 {@code @ConditionalOnMissingBean}：宿主仍可单独替换任一实现。
 *
 * <p><b>Redis 不可用时不降级到内存</b>：那会让两个副本各持一份状态却以为在共享，比宕机更糟。连接失败 → 请求 500，Spring Boot 自带的 {@code
 * RedisHealthIndicator} 会把 health 置为 DOWN。
 */
@AutoConfiguration(
    before = SparkRooterAutoConfiguration.class,
    after = RedisAutoConfiguration.class)
@ConditionalOnProperty(prefix = "spark.storage", name = "type", havingValue = "redis")
@ConditionalOnBean(StringRedisTemplate.class)
@EnableConfigurationProperties(SparkRedisProperties.class)
public class SparkRooterRedisAutoConfiguration {

  private static final Logger log =
      LoggerFactory.getLogger(SparkRooterRedisAutoConfiguration.class);

  public SparkRooterRedisAutoConfiguration() {
    log.info("spark storage: redis (Run / token / idempotency / memory)");
  }

  @Bean
  @ConditionalOnMissingBean(RunRepository.class)
  RunRepository sparkRooterRedisRunRepository(
      StringRedisTemplate redis, SchemaValidator validator, SparkRooterProperties props) {
    return new RedisRunRepository(redis, validator.mapper(), props.runtime().runTtl());
  }

  @Bean
  @ConditionalOnMissingBean(ConfirmationTokenStore.class)
  ConfirmationTokenStore sparkRooterRedisConfirmationTokenStore(
      StringRedisTemplate redis, SchemaValidator validator, Clock sparkRooterClock) {
    return new RedisConfirmationTokenStore(redis, validator.mapper(), sparkRooterClock);
  }

  @Bean
  @ConditionalOnMissingBean(ConversationMemory.class)
  ConversationMemory sparkRooterRedisConversationMemory(
      StringRedisTemplate redis, SchemaValidator validator, SparkRooterProperties props) {
    return new RedisConversationMemory(redis, validator.mapper(), props.runtime().memoryTtl());
  }

  /** 等待方轮询用的单线程调度器；等待只出现在同 key 并发提交的异常路径，一个线程足够。 */
  @Bean(destroyMethod = "shutdownNow")
  ScheduledExecutorService sparkRooterRedisIdempotencyPoller() {
    ScheduledThreadPoolExecutor ex =
        new ScheduledThreadPoolExecutor(
            1,
            r -> {
              Thread t = new Thread(r, "redis-idem-poll-");
              t.setDaemon(true);
              return t;
            });
    ex.setRemoveOnCancelPolicy(true);
    return ex;
  }

  @Bean
  @ConditionalOnMissingBean(IdempotencyStore.class)
  IdempotencyStore sparkRooterRedisIdempotencyStore(
      StringRedisTemplate redis,
      SchemaValidator validator,
      SparkRooterProperties props,
      SparkRedisProperties redisProps,
      ScheduledExecutorService sparkRooterRedisIdempotencyPoller) {
    return new RedisIdempotencyStore(
        redis,
        validator.mapper(),
        redisProps.claimTtl(),
        props.gateway().idempotencyTtl(),
        sparkRooterRedisIdempotencyPoller);
  }
}
