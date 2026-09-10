package com.sparkrooter.starter.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.registry.domain.ToolRegistryRepository;
import com.sparkrooter.spi.SelfCheck;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manifest 推导一致性自检：classpath {@code legacy-manifests/INDEX} 列出的每个旧手写 JSON，与 Registry 里同
 * toolId@version 的推导结果逐字段 diff（忽略 authorization / owner，两者本来就不同源）。宿主没有 legacy-manifests 时跳过并记日志。
 */
public final class ManifestParitySelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(ManifestParitySelfCheck.class);
  private static final String INDEX = "legacy-manifests/INDEX";
  private static final List<String> IGNORED = List.of("authorization", "owner");

  private final ToolRegistryRepository repo;
  private final SchemaValidator validator;

  public ManifestParitySelfCheck(ToolRegistryRepository repo, SchemaValidator validator) {
    this.repo = repo;
    this.validator = validator;
  }

  @Override
  public String name() {
    return "manifest parity";
  }

  @Override
  public void run() {
    List<String> files = index();
    if (files.isEmpty()) {
      log.info("selfcheck: manifest parity skipped (no legacy-manifests/INDEX)");
      return;
    }
    List<String> diffs = new ArrayList<>();
    for (String f : files) {
      JsonNode legacy = strip(validator.readClasspathJson("legacy-manifests/" + f));
      String id = legacy.path("toolId").asText();
      String ver = legacy.path("version").asText();
      JsonNode derived =
          repo.find(id, ver)
              .map(m -> strip(validator.mapper().valueToTree(m)))
              .orElseThrow(
                  () -> new IllegalStateException("derived manifest missing: " + id + "@" + ver));
      if (!legacy.equals(derived)) {
        diffs.add(id + "@" + ver + ": legacy=" + legacy + " derived=" + derived);
      }
    }
    if (!diffs.isEmpty()) {
      throw new IllegalStateException("manifest parity failed:\n" + String.join("\n", diffs));
    }
    log.info("selfcheck: manifest parity OK ({} manifests)", files.size());
  }

  /** 去掉不同源字段，并经字符串往返归一化数字节点类型（IntNode / LongNode）。 */
  private JsonNode strip(JsonNode n) {
    ObjectNode copy = n.deepCopy();
    IGNORED.forEach(copy::remove);
    try {
      return validator.mapper().readTree(validator.mapper().writeValueAsString(copy));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private List<String> index() {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(INDEX)) {
      if (in == null) {
        return List.of();
      }
      try (BufferedReader r =
          new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
        return r.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
