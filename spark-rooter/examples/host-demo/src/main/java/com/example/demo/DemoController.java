package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** 宿主自己的端点（受 DemoInterceptorOnlyGuard 拦截）：e2e ⑭' 用它证明拦截器只保护 Controller，不保护 spark 调用。 */
@RestController
public class DemoController {

  @GetMapping("/demo/whoami")
  public Map<String, String> whoami() {
    DemoUserContext.DemoUser u = DemoUserContext.currentOrDefault();
    return Map.of("viewer", u.userId(), "tenantId", u.tenantId());
  }
}
