package com.sparkrooter.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.runtime.domain.Plan;
import com.sparkrooter.runtime.domain.Run;
import com.sparkrooter.runtime.domain.RunState;
import com.sparkrooter.runtime.domain.Step;
import com.sparkrooter.spi.ConversationMemory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis Run 仓储与会话记忆：往返逐字段相等、TTL 生效、evictExpired 为空（Redis 负责过期）。 */
@EnabledIf("com.sparkrooter.redis.RedisTestBase#redisAvailable")
final class RedisRunRepositoryTest extends RedisTestBase {

  private static final Instant T0 = Instant.parse("2026-09-13T00:00:00Z");

  private final StringRedisTemplate redis = template();
  private final RedisRunRepository runs =
      new RedisRunRepository(redis, PlatformMapper.create(), SHORT);
  private final RedisConversationMemory memory =
      new RedisConversationMemory(redis, PlatformMapper.create(), SHORT);

  /**
   * 把一个走到 WAITING_CONFIRMATION 且带全部临时态的 Run 存进去再读出来，每个字段都要回来。
   *
   * <p>这就是"确认可以落到另一副本"的存储侧证明：另一副本凭这一条记录就能重建确认屏与重校验所需的一切。
   */
  @Test
  void saveThenFindRestoresEveryField() {
    String id = "run_" + scope();
    Run run = new Run(id, "conv", "sess", "关闭 10001", T0);
    run.transition(RunState.PLANNING, T0.plusSeconds(1));
    Plan plan =
        new Plan(
            "demo",
            List.of(
                new Step(1, "demo.item.get", "1.0.0", "查看", Map.of("itemId", "10001"), false),
                new Step(2, "demo.item.close", "1.0.0", "关闭", Map.of("itemId", "10001"), true)));
    run.attachPlan(
        plan,
        Map.of(
            "demo.item.get", "{\"type\":\"object\"}", "demo.item.close", "{\"type\":\"object\"}"),
        T0.plusSeconds(2));
    run.transition(RunState.EXECUTING, T0.plusSeconds(3));
    run.putStepOutput(
        "demo.item.get", "{\"status\":\"OPEN\",\"amount\":\"9.50\"}", T0.plusSeconds(4));
    run.advance(T0.plusSeconds(4));
    run.setCurrentUi("{\"screenId\":\"confirm\",\"components\":[]}", T0.plusSeconds(5));
    run.transition(RunState.WAITING_CONFIRMATION, T0.plusSeconds(6));

    runs.save(run);
    Run back = runs.find(id).orElseThrow();

    assertThat(back.runId()).isEqualTo(id);
    assertThat(back.conversationId()).isEqualTo("conv");
    assertThat(back.sessionId()).isEqualTo("sess");
    // 用户原话刻意不落共享存储（agent-safety §5 口径）；运行时规划后也没有路径再读它
    assertThat(back.message()).isNull();
    assertThat(back.createdAt()).isEqualTo(T0);
    assertThat(back.updatedAt()).isEqualTo(T0.plusSeconds(6));
    assertThat(back.state()).isEqualTo(RunState.WAITING_CONFIRMATION);
    assertThat(back.plan()).contains(plan);
    assertThat(back.nextSeq()).isEqualTo(2);
    assertThat(back.currentStep()).map(Step::toolId).contains("demo.item.close");
    assertThat(back.currentUi()).contains("{\"screenId\":\"confirm\",\"components\":[]}");
    assertThat(back.stepOutput("demo.item.get"))
        .contains("{\"status\":\"OPEN\",\"amount\":\"9.50\"}");
    assertThat(back.stepSchemas().keySet())
        .containsExactlyInAnyOrder("demo.item.get", "demo.item.close");
    assertThat(back.clarified()).isFalse();
    assertThat(back.failureCode()).isEmpty();
    // 反序列化出来的 Run 仍可继续状态机
    back.transition(RunState.EXECUTING, T0.plusSeconds(7));
    assertThat(back.state()).isEqualTo(RunState.EXECUTING);
  }

  @Test
  void failedRunKeepsFailureCodeAndClearedOutputs() {
    String id = "run_" + scope();
    Run run = new Run(id, "c", "s", "m", T0);
    run.transition(RunState.PLANNING, T0);
    run.putStepOutput("x", "{}", T0);
    run.fail("TOOL_EXECUTION_FAILED", T0.plusSeconds(1));
    run.clearStepOutputs();
    runs.save(run);

    Run back = runs.find(id).orElseThrow();
    assertThat(back.state()).isEqualTo(RunState.FAILED);
    assertThat(back.failureCode()).contains("TOOL_EXECUTION_FAILED");
    assertThat(back.stepOutputs()).isEmpty();
  }

  @Test
  void saveSetsTtlAndEvictExpiredIsNoop() {
    String id = "run_" + scope();
    runs.save(new Run(id, "c", "s", "m", T0));
    Long ttl = redis.getExpire(RedisRunRepository.PREFIX + id, TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isBetween(1L, SHORT.toSeconds());
    assertThat(runs.evictExpired()).isEmpty();
    assertThat(runs.find("run_nope")).isEmpty();
  }

  @Test
  void memoryRoundTripsAndIsKeyedBySessionAndConversation() {
    String s = scope();
    ConversationMemory.Memory m =
        new ConversationMemory.Memory(
            "demo",
            Map.of("item", "10001"),
            new ConversationMemory.LastTable("demo.item.list", List.of("10001", "10002"), "关闭"),
            T0);
    memory.put(s, "c1", m);

    assertThat(memory.find(s, "c1")).contains(m);
    // 同 conversationId 不同 sessionId 拿不到：会话号可伪造，隔离键来自宿主
    assertThat(memory.find(s + "-other", "c1")).isEmpty();
    Long ttl = redis.getExpire(RedisConversationMemory.PREFIX + s + "/c1", TimeUnit.SECONDS);
    assertThat(ttl).isNotNull().isBetween(1L, SHORT.toSeconds());
  }

  @Test
  void memoryWithNullLastTableRoundTrips() {
    String s = scope();
    ConversationMemory.Memory m = new ConversationMemory.Memory("demo", Map.of(), null, T0);
    memory.put(s, "c", m);
    assertThat(memory.find(s, "c")).contains(m);
  }
}
