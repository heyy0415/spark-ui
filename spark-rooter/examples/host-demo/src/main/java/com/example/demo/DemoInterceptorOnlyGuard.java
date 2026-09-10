package com.example.demo;

import com.sparkrooter.examples.support.DemoUserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * <b>反面教材</b>：只在 MVC 拦截器里做权限（guest 不能访问 /demo/**，包括与 order.list.search 同能力的 /demo/orders）。它对 spark-rooter 的工具调用<b>无效</b>——spark 经代理反射直接调用
 * @SparkTool 方法，不经过 Controller / 拦截器。e2e ⑭' 用它证明「宿主权限必须是方法级」（spec §2.4）。
 */
@Component
public class DemoInterceptorOnlyGuard implements HandlerInterceptor {

  @Override
  public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
    if (req.getRequestURI().startsWith("/demo/") && "guest".equals(DemoUserContext.userId())) {
      res.setStatus(HttpServletResponse.SC_FORBIDDEN);
      return false;
    }
    return true;
  }
}
