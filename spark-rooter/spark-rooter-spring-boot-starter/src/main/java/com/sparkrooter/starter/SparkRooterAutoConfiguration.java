package com.sparkrooter.starter;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

/**
 * spark-rooter 自动装配入口（META-INF/spring/…AutoConfiguration.imports）。宿主只需引入 starter 坐标；平台 Bean 全部由
 * {@link Import} 的配置类以 @Bean 声明（不依赖包扫描），默认实现全部 @ConditionalOnMissingBean，宿主定义同类型 Bean 即覆盖。Web 端点见
 * {@link SparkRooterWebMvcAutoConfiguration}。
 */
@AutoConfiguration
@EnableConfigurationProperties(SparkRooterProperties.class)
@Import({
  ContractsBeans.class,
  RegistryBeans.class,
  GatewayBeans.class,
  RuntimeBeans.class,
  ToolBeans.class,
  MetricsBeans.class,
  SelfCheckBeans.class
})
public class SparkRooterAutoConfiguration {}
