package com.example.demo;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 单镜像 Demo 部署时前端 dist 被放进 classpath:/static，由 Spring Boot 默认静态资源处理托管。 SPA 只有 "/" 一个路由（DEV 才有
 * /dev/schema），未知路径交给前端的 NotFound 页：把 404 的非 API 路径转发到 index.html。 本地开发（vite dev 代理）不受影响。
 */
@Configuration
public class DemoStaticConfig implements WebMvcConfigurer {

  @Override
  public void addViewControllers(ViewControllerRegistry registry) {
    // 只映射根路径；/agent /internal /demo /actuator 各有 Controller，不会走到这里
    registry.addViewController("/").setViewName("forward:/index.html");
  }
}
