package com.sparkrooter.starter;

import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.contracts.tool.ManifestDeriver;
import com.sparkrooter.gateway.infra.transport.InProcessToolTransport;
import com.sparkrooter.registry.application.RegisterToolUseCase;
import com.sparkrooter.runtime.application.meta.ToolMetaRegistry;
import com.sparkrooter.spi.ToolNameSink;
import com.sparkrooter.starter.tool.SparkToolScanner;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @SparkTool 扫描与 Manifest 推导。开启 AspectJ 自动代理，宿主的 @Aspect 才能切到工具方法（宿主自己开了也不冲突）。
 */
@Configuration(proxyBeanMethods = false)
@EnableAspectJAutoProxy
class ToolBeans {

  @Bean
  ManifestDeriver sparkRooterManifestDeriver(
      SchemaValidator validator, SparkRooterProperties props) {
    return new ManifestDeriver(validator, props.ownerTeam());
  }

  @Bean
  SparkToolScanner sparkRooterToolScanner(
      ApplicationContext context,
      ManifestDeriver deriver,
      RegisterToolUseCase register,
      InProcessToolTransport transport,
      ToolMetaRegistry meta,
      ObjectProvider<ToolNameSink> nameSinks) {
    return new SparkToolScanner(
        context, deriver, register, transport, meta, nameSinks.orderedStream().toList());
  }
}
