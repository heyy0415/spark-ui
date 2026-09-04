package com.strato.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.strato")
public class StratoApplication {
  public static void main(String[] args) {
    SpringApplication.run(StratoApplication.class, args);
  }
}
