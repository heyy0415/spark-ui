package com.sparkrooter.contracts;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 平台自建的 {@link ObjectMapper}，hub 与 provider 两侧共用。
 *
 * <p>共用的理由不是省代码，而是<b>两侧序列化口径必须一致</b>：同一个 {@code @SparkTool} 方法在单体形态与 微服务形态下推导出的 Manifest
 * 必须逐字段相同，否则同一工具会因为部署形态不同而产生不同的 inputSchema / 金额表示，跨形态迁移时静默破坏契约。
 *
 * <p>不注册为 Spring 的 {@code ObjectMapper} Bean：那会与宿主自己的 Jackson 自动配置互相干扰。 平台各 Bean 经 {@code
 * validator.mapper()} 取用。
 */
public final class PlatformMapper {

  private PlatformMapper() {}

  /**
   * 构造平台 mapper。
   *
   * <p>配置项及理由：JavaTimeModule / Jdk8Module（record 组件 {@code Optional<T>} 缺键 → empty 而非 null）、
   * 金额一律两位小数字符串（contracts.md §3）、时间不用时间戳、未知字段即失败（契约 {@code additionalProperties:false} 的 Java 侧对应）。
   *
   * <p>模块<b>显式注册</b>，不用 {@code findAndRegisterModules()}：后者按 ServiceLoader 扫全 classpath，
   * 任一被发现的模块实例化失败就整体抛 {@code ServiceConfigurationError}（实测被 classpath 上一个 无 joda-time 依赖的
   * JodaModule 打挂）。宿主 classpath 不可控，平台 mapper 必须只认自己要的模块。
   */
  public static ObjectMapper create() {
    ObjectMapper mapper =
        new ObjectMapper()
            .registerModule(new JavaTimeModule())
            // record 组件 Optional<T>：缺键 → Optional.empty()（否则为 null）
            .registerModule(new Jdk8Module())
            // 金额一律两位小数字符串（contracts.md §3），与手写 Manifest 的 amountText() 口径一致
            .registerModule(
                new SimpleModule().addSerializer(BigDecimal.class, new MoneySerializer()))
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setDefaultPropertyInclusion(
                JsonInclude.Value.construct(
                    JsonInclude.Include.NON_ABSENT, JsonInclude.Include.ALWAYS));
    return mapper;
  }

  /** BigDecimal → "128.00"：与 amountText() 口径一致。 */
  static final class MoneySerializer extends JsonSerializer<BigDecimal> {
    @Override
    public void serialize(BigDecimal value, JsonGenerator gen, SerializerProvider p)
        throws IOException {
      gen.writeString(value.setScale(2, RoundingMode.HALF_UP).toPlainString());
    }
  }
}
