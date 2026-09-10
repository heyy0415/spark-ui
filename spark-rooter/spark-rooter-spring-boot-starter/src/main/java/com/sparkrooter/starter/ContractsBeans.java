package com.sparkrooter.starter;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sparkrooter.contracts.SchemaValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 契约校验器。平台自建 ObjectMapper（JavaTimeModule、禁 timestamps、FAIL_ON_UNKNOWN_PROPERTIES、NON_ABSENT），不借宿主的，
 * 也不注册为 ObjectMapper Bean（否则会与宿主 Jackson 自动配置互相干扰）；平台各 Bean 经 {@code validator.mapper()} 取用。
 */
@Configuration(proxyBeanMethods = false)
class ContractsBeans {

  static ObjectMapper platformMapper() {
    return new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .setDefaultPropertyInclusion(
            JsonInclude.Value.construct(
                JsonInclude.Include.NON_ABSENT, JsonInclude.Include.ALWAYS));
  }

  @Bean
  @ConditionalOnMissingBean(SchemaValidator.class)
  SchemaValidator sparkRooterSchemaValidator() {
    return new SchemaValidator(platformMapper());
  }
}
