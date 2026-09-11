package com.sparkrooter.contracts.infra.selfcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sparkrooter.contracts.SchemaValidator;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 契约自检：示例文件名到契约名的最长匹配；副本内全部示例经 run() 校验通过。 */
final class ContractsSelfCheckTest {

  @Test
  void contractOfPicksLongestMatchingName() {
    assertThat(ContractsSelfCheck.contractOf("ui-schema.example.json")).isEqualTo("ui-schema");
    assertThat(ContractsSelfCheck.contractOf("ui-schema.order-table.example.json"))
        .isEqualTo("ui-schema");
    assertThat(ContractsSelfCheck.contractOf("sse-events.run-failed.example.json"))
        .isEqualTo("sse-events");
    assertThat(ContractsSelfCheck.contractOf("tool-manifest.refund-create.example.json"))
        .isEqualTo("tool-manifest");
  }

  @Test
  void runValidatesEveryBundledExample() {
    ContractsSelfCheck check = new ContractsSelfCheck(new SchemaValidator(new ObjectMapper()));
    assertThat(check.name()).isEqualTo("contracts");
    assertThatCode(check::run).doesNotThrowAnyException();
  }

  @Test
  void indexListsAllTwentySevenExamples() throws IOException {
    // INDEX 由 sync-contracts 生成；数量与 .harness/contracts/examples 一致（e2e 断言同一数字）
    try (InputStream in =
        getClass().getClassLoader().getResourceAsStream("contracts/examples/INDEX")) {
      assertThat(in).isNotNull();
      List<String> lines =
          new String(in.readAllBytes(), StandardCharsets.UTF_8)
              .lines()
              .filter(l -> !l.isBlank())
              .toList();
      assertThat(lines).hasSize(27).allMatch(f -> f.endsWith(".example.json"));
    }
  }
}
