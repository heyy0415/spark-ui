package com.sparkrooter.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.sparkrooter")
public class SparkRooterApplication {
  public static void main(String[] args) {
    SpringApplication.run(SparkRooterApplication.class, args);
  }
}
