package com.strato.domain.order.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.strato.spi.ToolManifestSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import org.springframework.stereotype.Component;

/** 暴露本模块的 Manifest，供 Registry 启动时拉取。本模块不依赖 registry。 */
@Component
public class OrderManifestSource implements ToolManifestSource {

  private static final List<String> FILES =
      List.of(
          "order.detail.get.json",
          "order.list.search.json",
          "order.logistics.get.json",
          "order.delete.json");

  private final ObjectMapper mapper;

  public OrderManifestSource(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public List<JsonNode> manifests() {
    return FILES.stream().map(this::read).toList();
  }

  private JsonNode read(String file) {
    String path = "tool-manifests/" + file;
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
      if (in == null) {
        throw new IllegalStateException("missing manifest resource " + path);
      }
      return mapper.readTree(in);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
