package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.registry.api.ConfirmationCoveragePolicy;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.registry.application.RegistrationGuard;
import com.sparkrooter.registry.application.SearchToolsUseCase;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.registry.infra.InMemoryToolRegistryRepository;
import com.sparkrooter.registry.infra.RegistryToolResolver;
import com.sparkrooter.registry.infra.StartupManifestRegistrar;
import com.sparkrooter.spi.ProviderAuth;
import com.sparkrooter.spi.SharedSecretProviderAuth;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolManifestSource;
import com.sparkrooter.spi.ToolNameSink;
import com.sparkrooter.spi.ToolResolver;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 控制面（Registry）装配。存储默认内存；ToolAccessPolicy 由宿主可选提供。 */
@Configuration(proxyBeanMethods = false)
class RegistryBeans {

  @Bean
  @ConditionalOnMissingBean(ToolRegistryRepository.class)
  ToolRegistryRepository sparkRooterToolRegistryRepository() {
    return new InMemoryToolRegistryRepository();
  }

  /**
   * 远程注册的准入守卫。
   *
   * <p>{@link ProviderAuth} 是可选 Bean：单体宿主不装配它，此时守卫**拒绝一切 http 注册**而不是放行——
   * 缺认证就放行等于给未配置认证的部署留一个静默后门（feat-provider-http-transport §1.2）。
   */
  /**
   * 远程 provider 的认证实现，仅当配了 {@code spark.providers.tokens.*} 时创建。
   *
   * <p>没配 = 本 hub 不接受远程工具（{@code RegistrationGuard} 拒绝 http 注册、不装配 {@code
   * HttpToolTransport}）。单体宿主无需任何配置，行为不变。
   *
   * <p>同一个 Bean 同时供入站校验（注册端点）与出站取令牌（HttpToolTransport 按被调 serviceName 取）——hub 调多个 provider，各自密钥不同。
   */
  @Bean
  @ConditionalOnMissingBean
  ProviderAuth sparkRooterProviderAuth(SparkRooterProperties props) {
    Map<String, String> tokens = props.providers().tokens();
    if (tokens == null || tokens.isEmpty()) {
      // 未配 = 本 hub 不接受远程工具。返回 null 让 Spring 不注册这个 Bean，于是
      // RegistrationGuard 拒绝 http 注册、HttpToolTransport 不装配（@ConditionalOnBean）。
      //
      // 不用 @ConditionalOnProperty 探测：Map 类型属性没有可靠的"键存在"表达式
      // （tokens[0] 是 List 的语法，对 Map 永远不成立——首版就是这么写的，导致
      // hub 明明配了密钥却不装配认证，provider 全部推送 401）。
      return null;
    }
    return new SharedSecretProviderAuth(tokens);
  }

  @Bean
  @ConditionalOnMissingBean
  RegistrationGuard sparkRooterRegistrationGuard(ObjectProvider<ProviderAuth> auth) {
    return new RegistrationGuard(auth.getIfAvailable());
  }

  @Bean
  RegisterToolUseCase sparkRooterRegisterToolUseCase(
      ToolRegistryRepository repo, SchemaValidator validator, ConfirmationCoveragePolicy coverage) {
    return new RegisterToolUseCase(repo, validator, coverage);
  }

  @Bean
  SearchToolsUseCase sparkRooterSearchToolsUseCase(
      ToolRegistryRepository repo, ObjectProvider<ToolAccessPolicy> access) {
    return new SearchToolsUseCase(repo, access);
  }

  @Bean
  @ConditionalOnMissingBean(ToolResolver.class)
  ToolResolver sparkRooterToolResolver(ToolRegistryRepository repo, SchemaValidator validator) {
    return new RegistryToolResolver(repo, validator);
  }

  @Bean
  StartupManifestRegistrar sparkRooterStartupManifestRegistrar(
      ObjectProvider<ToolManifestSource> sources,
      RegisterToolUseCase register,
      ObjectProvider<ToolNameSink> nameSinks) {
    return new StartupManifestRegistrar(
        sources.orderedStream().toList(), register, nameSinks.orderedStream().toList());
  }
}
