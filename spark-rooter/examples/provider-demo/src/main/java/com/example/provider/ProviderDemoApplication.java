package com.example.provider;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例 provider：一个只提供订单能力的独立业务服务。
 *
 * <p>与 {@code host-demo} 的关键差别——本进程<b>没有</b> Agent Runtime、没有 Registry、没有 Gateway、
 * 没有 LLM 客户端。它只做两件事：启动时把 {@code @SparkTool} 推导的 Manifest 推给 hub，运行时接收
 * hub 的调用并执行。规划与治理全在 hub。
 *
 * <p>只扫本包：provider 平台 Bean 全部来自 starter 自动装配，领域工具（{@link InventoryTools}）
 * 就在本包内——真实业务服务的领域类同样在自己的包里，自然被扫到。
 */
@SpringBootApplication
public class ProviderDemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(ProviderDemoApplication.class, args);
  }
}
