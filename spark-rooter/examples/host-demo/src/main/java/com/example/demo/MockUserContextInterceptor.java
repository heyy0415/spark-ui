package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 宿主自己的「登录」：请求头 X-Demo-User → DemoUserContext（ThreadLocal）。缺省 user_001（admin）；guest 无 admin 角色。
 * spark-rooter 对此一无所知，工具方法体与切面从 DemoUserContext 读用户。
 */
@Component
public class MockUserContextInterceptor implements HandlerInterceptor {

  static final String HEADER = "X-Demo-User";
  private static final String TENANT = "tenant_001";

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    String user = req.getHeader(HEADER);
    if (user == null || user.isBlank()) {
      DemoUserContext.set(DemoUserContext.DEFAULT);
    } else {
      Set<String> roles = "user_001".equals(user) ? Set.of("admin") : Set.of();
      DemoUserContext.set(new DemoUserContext.DemoUser(user, TENANT, roles));
    }
    return true;
  }

  @Override
  public void afterCompletion(
      HttpServletRequest req, HttpServletResponse res, Object handler, Exception ex) {
    DemoUserContext.clear();
  }
}
