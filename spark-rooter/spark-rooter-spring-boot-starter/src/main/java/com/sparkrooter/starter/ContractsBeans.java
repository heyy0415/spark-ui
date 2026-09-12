package com.sparkrooter.starter;

import com.sparkrooter.contracts.PlatformMapper;
import com.sparkrooter.contracts.SchemaValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 契约校验器。mapper 配置在 {@link PlatformMapper}（与 provider 侧共用，保证两种形态推导出的 Manifest 逐字段一致）；不借宿主的
 * ObjectMapper，也不注册为 ObjectMapper Bean（否则会与宿主 Jackson 自动配置互相干扰）；平台各 Bean 经 {@code
 * validator.mapper()} 取用。
 */
@Configuration(proxyBeanMethods = false)
class ContractsBeans {

  @Bean
  @ConditionalOnMissingBean(SchemaValidator.class)
  SchemaValidator sparkRooterSchemaValidator() {
    return new SchemaValidator(PlatformMapper.create());
  }
}
