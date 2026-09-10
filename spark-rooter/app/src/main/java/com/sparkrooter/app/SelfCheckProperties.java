package com.sparkrooter.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "spark.selfcheck")
public record SelfCheckProperties(boolean enabled) {}
