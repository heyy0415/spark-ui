package com.strato.app;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strato.selfcheck")
public record SelfCheckProperties(boolean enabled) {}
