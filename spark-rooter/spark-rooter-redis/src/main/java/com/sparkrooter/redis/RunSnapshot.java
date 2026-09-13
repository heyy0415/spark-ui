package com.sparkrooter.redis;

import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunState;
import java.time.Instant;
import java.util.Map;

/**
 * {@link Run} 的持久化形状。不直接序列化聚合：聚合的构造器有"新建"语义、字段私有且带不变量校验，与 Jackson 的 getter/setter 假设不匹配；单独一个 record
 * 让存储格式显式、可演进，字段改名不会静默破坏已存的键。
 *
 * <p>三个 JSON 字段（currentUi / stepOutputs / stepSchemas）在 Run 里就是字符串，这里原样存——不做二次解析， 反序列化时也不校验它们是不是合法
 * JSON（写入方已保证）。
 */
public record RunSnapshot(
    String runId,
    String conversationId,
    String sessionId,
    Instant createdAt,
    Instant updatedAt,
    RunState state,
    Plan plan,
    int nextSeq,
    String failureCode,
    String currentUi,
    Map<String, String> stepOutputs,
    Map<String, String> stepSchemas,
    boolean clarified) {

  public static RunSnapshot of(Run run) {
    return new RunSnapshot(
        run.runId(),
        run.conversationId(),
        run.sessionId(),
        run.createdAt(),
        run.updatedAt(),
        run.state(),
        run.plan().orElse(null),
        run.nextSeq(),
        run.failureCode().orElse(null),
        run.currentUi().orElse(null),
        run.stepOutputs(),
        run.stepSchemas(),
        run.clarified());
  }

  public Run toRun() {
    return Run.restore(
        runId,
        conversationId,
        sessionId,
        null,
        createdAt,
        updatedAt,
        state,
        plan,
        nextSeq,
        failureCode,
        currentUi,
        stepOutputs,
        stepSchemas,
        clarified);
  }
}
