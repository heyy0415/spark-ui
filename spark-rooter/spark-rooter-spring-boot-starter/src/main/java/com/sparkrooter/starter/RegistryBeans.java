package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.registry.application.SearchToolsUseCase;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.registry.infra.InMemoryToolRegistryRepository;
import com.sparkrooter.registry.infra.RegistryToolResolver;
import com.sparkrooter.registry.infra.StartupManifestRegistrar;
import com.sparkrooter.spi.ToolAccessPolicy;
import com.sparkrooter.spi.ToolManifestSource;
import com.sparkrooter.spi.ToolNameSink;
import com.sparkrooter.spi.ToolResolver;
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

  @Bean
  RegisterToolUseCase sparkRooterRegisterToolUseCase(
      ToolRegistryRepository repo, SchemaValidator validator) {
    return new RegisterToolUseCase(repo, validator);
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
