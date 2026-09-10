package com.sparkrooter.spi;

/** 屏生成上下文：只有 Run 标识，无业务数据、无用户。 */
public record ScreenContext(String runId) {}
