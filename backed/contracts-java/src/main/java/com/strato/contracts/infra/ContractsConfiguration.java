package com.strato.contracts.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.strato.contracts.SchemaValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 把无框架依赖的 SchemaValidator 暴露为 Bean；ObjectMapper 由 Spring Boot 自动配置提供。 */
@Configuration
public class ContractsConfiguration {

  @Bean
  public SchemaValidator schemaValidator(ObjectMapper mapper) {
    return new SchemaValidator(mapper);
  }
}
