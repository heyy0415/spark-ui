package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 示例宿主。主类包 com.example.demo，不扫 com.sparkrooter：平台 Bean 全部来自 starter 自动装配；四个示例领域的 @Service（含 @SparkTool
 * 方法）、ScreenBuilder、ConfirmationRecheck 由本类显式扫描进来（真实宿主的领域类在自己的包里，自然被扫到）。
 */
@SpringBootApplication(scanBasePackages = {"com.example.demo", "com.sparkrooter.examples"})
public class HostDemoApplication {
  public static void main(String[] args) {
    SpringApplication.run(HostDemoApplication.class, args);
  }
}
