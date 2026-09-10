package com.sparkrooter.spi;

/** 屏生成上下文：只有标识信息，无业务数据。 */
public record ScreenContext(String runId, String userId, String tenantId) {}
