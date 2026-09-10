package com.example.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 注册两个拦截器：先建立用户上下文，再做（反面教材的）路径级权限。 */
@Configuration
public class DemoWebConfig implements WebMvcConfigurer {

  private final MockUserContextInterceptor userContext;
  private final DemoInterceptorOnlyGuard guard;

  public DemoWebConfig(MockUserContextInterceptor userContext, DemoInterceptorOnlyGuard guard) {
    this.userContext = userContext;
    this.guard = guard;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(userContext).order(0);
    registry.addInterceptor(guard).order(1);
  }
}
