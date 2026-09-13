package com.sparkrooter.redis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.UncheckedIOException;

/** 本模块统一的 JSON 编解码：失败一律转成 unchecked，调用方不必到处 try/catch。 */
final class Json {

  private final ObjectMapper mapper;

  Json(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("cannot serialize " + value.getClass().getSimpleName(), e);
    }
  }

  <T> T read(String json, Class<T> type) {
    try {
      return mapper.readValue(json, type);
    } catch (JsonProcessingException e) {
      throw new UncheckedIOException("stored value is not a " + type.getSimpleName(), e);
    }
  }
}
