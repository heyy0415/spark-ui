package com.sparkrooter.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code spark.storage.redis.*}：Redis 存储专有的参数。TTL 类参数（runTtl / memoryTtl / idempotencyTtl）沿用 {@code
 * spark.runtime.*} / {@code spark.gateway.*} 里已有的值，不重复定义——同一个概念不该有两个开关。
 *
 * @param claimTtl 幂等占位 key 的存活时间。<b>必须大于任何工具的 {@code @SparkRisk.timeoutMs}</b>：占位在 owner 还没执行完时过期，
 *     等待方会重新 claim 成为 owner，同一个写操作就执行了两次。默认 5 分钟远大于示例里的 5 秒；宿主若有分钟级长任务请同步调大
 */
@ConfigurationProperties(prefix = "spark.storage.redis")
public record SparkRedisProperties(@DefaultValue("5m") Duration claimTtl) {}
