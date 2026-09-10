package com.sparkrooter.spi;

/** 启动自检项。各模块在自己的 infra/selfcheck 提供 Bean；app 的 SelfCheckRunner 统一执行。失败抛异常。 */
public interface SelfCheck {
  String name();

  void run();
}
