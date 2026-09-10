package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import com.sparkrooter.spi.annotation.SparkTool;
import org.springframework.stereotype.Service;

/**
 * 宿主专有工具：回显当前用户（e2e ㉖ 断言上下文传播：工作线程里读到的用户 == 请求头 X-Demo-User）。放在单独工具上，不污染 12 个基线工具的
 * Manifest（评审 N-2）。
 */
@Service
public class DemoTools {

  public record In() {}

  public record Out(String viewer, String tenantId) {}

  @SparkTool(
      id = "demo.whoami",
      version = "1.0.0",
      domain = "demo",
      name = "当前用户",
      description = "示例宿主专用：返回 spark 工作线程里看到的当前用户与租户，用于验证宿主上下文传播。")
  public Out whoami(In in) {
    DemoUserContext.DemoUser u = DemoUserContext.currentOrDefault();
    return new Out(u.userId(), u.tenantId());
  }
}
