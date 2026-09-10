package com.example.tmp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 临时验收宿主（T08）：主类不在 com.sparkrooter 下，平台 Bean 全部来自 starter 自动装配，不靠包扫描。 只扫示例领域模块（其 handler / 屏 /
 * 重校验仍是 \@Component，T09b 改注解形态后本模块删除，由 examples/host-demo 接替）。
 */
@SpringBootApplication(scanBasePackages = "com.sparkrooter.examples")
public class SparkRooterApplication {
  public static void main(String[] args) {
    SpringApplication.run(SparkRooterApplication.class, args);
  }
}
