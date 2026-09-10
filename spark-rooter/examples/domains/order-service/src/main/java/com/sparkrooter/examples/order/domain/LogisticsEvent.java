package com.sparkrooter.examples.order.domain;

import java.time.Instant;

/** 一条物流轨迹事件；同单内 seq 从 1 连续递增，carrier / trackingNo 在同单内相同。 */
public record LogisticsEvent(
    String eventId,
    String carrier,
    String trackingNo,
    int seq,
    Instant eventTime,
    String location,
    String description) {}
