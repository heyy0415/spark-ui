package com.sparkrooter.contracts.infra.selfcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.sparkrooter.contracts.SchemaValidator;
import com.sparkrooter.spi.SelfCheck;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 启动自检：9 个契约全部可编译，INDEX 列出的全部示例通过各自契约校验。 示例文件名规则：{name}.example.json 或
 * {name}.{variant}.example.json（与 check-contracts.mjs 一致）。
 */
public class ContractsSelfCheck implements SelfCheck {

  private static final Logger log = LoggerFactory.getLogger(ContractsSelfCheck.class);
  private static final String INDEX = "contracts/examples/INDEX";

  private final SchemaValidator validator;

  public ContractsSelfCheck(SchemaValidator validator) {
    this.validator = validator;
  }

  @Override
  public String name() {
    return "contracts";
  }

  @Override
  public void run() {
    List<String> examples = listExamples();
    int validated = 0;
    for (String file : examples) {
      String contract = contractOf(file);
      JsonNode data = validator.readClasspathJson("contracts/examples/" + file);
      validator.assertValid(contract, data);
      validated++;
    }
    log.info(
        "selfcheck: contracts {} schemas, {} examples OK",
        SchemaValidator.CONTRACT_NAMES.size(),
        validated);
  }

  /** {name}.example.json → name；{name}.{variant}.example.json → name（取最长匹配的契约名）。 */
  static String contractOf(String file) {
    String stem = file.substring(0, file.length() - ".example.json".length());
    String best = null;
    for (String name : SchemaValidator.CONTRACT_NAMES) {
      if ((stem.equals(name) || stem.startsWith(name + "."))
          && (best == null || name.length() > best.length())) {
        best = name;
      }
    }
    if (best == null) {
      throw new IllegalStateException("example without matching contract: " + file);
    }
    return best;
  }

  /** jar 内无法列目录：INDEX（每行一个示例文件名）由 .harness/scripts/sync-contracts.mjs 与契约副本一起生成。 */
  private List<String> listExamples() {
    try (InputStream in = getClass().getClassLoader().getResourceAsStream(INDEX)) {
      if (in == null) {
        throw new IllegalStateException(
            "missing " + INDEX + "; run pnpm -C .harness run sync-contracts");
      }
      List<String> out = new ArrayList<>();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
        String t = line.trim();
        if (!t.isEmpty()) {
          out.add(t);
        }
      }
      return out;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
