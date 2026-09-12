package com.sparkrooter.provider;

import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.tool.ManifestDeriver;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.RunContextPropagator;
import com.sparkrooter.spi.SharedSecretProviderAuth;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * provider 侧自动装配。宿主引入本 starter + 配 {@code spark.provider.*} 即可把 {@code @SparkTool} 方法 暴露给远端 hub。
 *
 * <p>不含规划 / 注册中心 / 网关：那些是 hub 的职责。provider 只做「声明工具 + 执行工具」。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SparkProviderProperties.class)
public class SparkProviderAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SparkProviderAutoConfiguration.class);

  /** 契约校验器；mapper 与 hub 侧共用 PlatformMapper，保证两种形态推导的 Manifest 逐字段一致。 */
  @Bean
  @ConditionalOnMissingBean
  SchemaValidator sparkProviderSchemaValidator() {
    return new SchemaValidator(PlatformMapper.create());
  }

  @Bean
  @ConditionalOnMissingBean
  ProviderAuth sparkProviderAuth(SparkProviderProperties props) {
    requireConfig(props.serviceName(), "spark.provider.service-name");
    requireConfig(props.token(), "spark.provider.token");
    return new SharedSecretProviderAuth(Map.of(props.serviceName(), props.token()));
  }

  @Bean
  ManifestDeriver sparkProviderManifestDeriver(
      SchemaValidator validator, SparkProviderProperties props) {
    return new ManifestDeriver(validator, props.ownerTeam());
  }

  @Bean
  ProviderToolRegistry sparkProviderToolRegistry() {
    return new ProviderToolRegistry();
  }

  @Bean
  ProviderToolScanner sparkProviderToolScanner(
      ApplicationContext context,
      ManifestDeriver deriver,
      ProviderToolRegistry registry,
      SparkProviderProperties props) {
    requireConfig(props.serviceName(), "spark.provider.service-name");
    warnOnInsecureBaseUrl(props.baseUrl());
    return new ProviderToolScanner(context, deriver, registry, props);
  }

  @Bean
  ManifestPublisher sparkProviderManifestPublisher(
      ProviderToolRegistry registry, SparkProviderProperties props, ProviderAuth auth) {
    requireConfig(props.hubUrl(), "spark.provider.hub-url");
    return new ManifestPublisher(registry, props, auth);
  }

  @Bean
  @ConditionalOnMissingBean
  ProviderIdempotencyStore sparkProviderIdempotencyStore() {
    return new ProviderIdempotencyStore();
  }

  /**
   * 执行端点。{@code @ConditionalOnClass} 守护：宿主没有 web 栈时本 Bean 不装配， 但**必须显式
   * WARN**——工具注册成功却永远调不通是极难排查的故障（spec §8）。
   */
  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnClass(name = "org.springframework.web.bind.annotation.RestController")
  ProviderInvokeController sparkProviderInvokeController(
      ProviderToolRegistry registry,
      ProviderAuth auth,
      SparkProviderProperties props,
      ProviderIdempotencyStore idempotency,
      SchemaValidator validator) {
    return new ProviderInvokeController(registry, auth, props, idempotency, validator);
  }

  /**
   * 宿主注册了 {@link RunContextPropagator} 时告警：它<b>不跨进程生效</b>。
   *
   * <p>{@code capture()} 返回不透明 {@code Object}，设计上不可序列化。宿主若以为自己的 ThreadLocal / SecurityContextHolder
   * 能传到 provider，基于它的鉴权判定会静默走默认分支——这是静默失效， 比抛异常危险得多。provider 要拿身份只能靠 hub 传来的 {@code sessionId}
   * 或自己的网关鉴权。
   */
  @Bean
  ProviderContextWarning sparkProviderContextWarning(
      ObjectProvider<RunContextPropagator> propagators) {
    return new ProviderContextWarning(propagators.getIfAvailable());
  }

  private static void requireConfig(String value, String key) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(
          "spark-provider: missing required config " + key + "; refusing to start");
    }
  }

  /** 明文 HTTP 允许但必须显式告警：工具返回值可能含敏感信息，生产应用 HTTPS 或 mTLS。 */
  private static void warnOnInsecureBaseUrl(String baseUrl) {
    if (baseUrl != null && baseUrl.startsWith("http://")) {
      log.warn(
          "spark-provider: baseUrl {} is plaintext HTTP; tool payloads travel unencrypted."
              + " Use HTTPS or mTLS in production.",
          baseUrl);
    }
  }

  /** 只为在启动期打一条告警而存在的 Bean（见 {@link #sparkProviderContextWarning}）。 */
  static final class ProviderContextWarning {
    ProviderContextWarning(RunContextPropagator propagator) {
      if (propagator != null) {
        log.warn(
            "spark-provider: a RunContextPropagator bean ({}) is present, but it does NOT apply"
                + " across processes — host ThreadLocals are empty inside this provider. Derive"
                + " identity from the sessionId passed by the hub, or from your own gateway auth.",
            propagator.getClass().getName());
      }
    }
  }
}
