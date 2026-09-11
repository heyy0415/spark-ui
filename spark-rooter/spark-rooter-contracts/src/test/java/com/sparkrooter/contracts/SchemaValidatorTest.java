package com.sparkrooter.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;
import com.sparkrooter.contracts.model.ToolManifest;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** 契约校验器：9 个契约可加载、示例全部通过、边界绑定拒绝空体、内嵌 schema 校验可用。 */
final class SchemaValidatorTest {

  private final SchemaValidator validator = new SchemaValidator(new ObjectMapper());

  @Test
  void loadsAllNineContracts() {
    assertThat(SchemaValidator.CONTRACT_NAMES).hasSize(9);
    for (String name : SchemaValidator.CONTRACT_NAMES) {
      // 空对象一定不满足任何契约（都有 required），但能拿到 ValidationMessage 说明 schema 已编译可用
      Set<ValidationMessage> errors =
          validator.validate(name, validator.mapper().createObjectNode());
      assertThat(errors).as(name).isNotEmpty();
    }
  }

  @Test
  void unknownContractIsRejected() {
    assertThatThrownBy(
            () -> validator.validate("no-such-contract", validator.mapper().createObjectNode()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("no-such-contract");
  }

  @Test
  void bundledExampleValidatesAgainstItsContract() {
    JsonNode manifest =
        validator.readClasspathJson("contracts/examples/tool-manifest.example.json");
    validator.assertValid("tool-manifest", manifest);
    ToolManifest bound = validator.bind("tool-manifest", null, manifest, ToolManifest.class);
    assertThat(bound.toolId()).isEqualTo("refund.eligibility.check");
    assertThat(bound.risk().level()).isEqualTo(ToolManifest.RiskLevel.low);
  }

  @Test
  void assertValidThrowsWithContractNameAndViolations() {
    ObjectNode bad = validator.mapper().createObjectNode().put("toolId", "x");
    assertThatThrownBy(() -> validator.assertValid("tool-manifest", bad))
        .isInstanceOf(ContractViolationException.class)
        .satisfies(
            e -> {
              ContractViolationException cve = (ContractViolationException) e;
              assertThat(cve.contractName()).isEqualTo("tool-manifest");
              assertThat(cve.violations()).isNotEmpty();
            });
  }

  @Test
  void bindRejectsNullOrMissingBody() {
    assertThatThrownBy(() -> validator.bind("intent-request", null, null, JsonNode.class))
        .isInstanceOf(ContractViolationException.class);
    assertThatThrownBy(
            () ->
                validator.bind(
                    "intent-request", null, validator.mapper().nullNode(), JsonNode.class))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void bindWithFragmentValidatesOnlyThatDefinition() {
    JsonNode search = validator.readClasspathJson("contracts/examples/tool-search.example.json");
    JsonNode request = search.path("request");
    assertThat(request.isMissingNode()).isFalse();
    // 只校验 request 子定义：整份契约（request+response）用同一节点会失败，子定义应通过
    JsonNode bound = validator.bind("tool-search", "#/$defs/request", request, JsonNode.class);
    assertThat(bound).isEqualTo(request);
  }

  @Test
  void inlineSchemaValidationReportsViolations() {
    ObjectNode schema = validator.mapper().createObjectNode();
    schema.put("type", "object");
    schema.putArray("required").add("itemId");
    schema.putObject("properties").putObject("itemId").put("type", "string");

    Set<ValidationMessage> ok =
        validator.validateWithInlineSchema(
            schema, validator.mapper().createObjectNode().put("itemId", "10001"));
    Set<ValidationMessage> missing =
        validator.validateWithInlineSchema(schema, validator.mapper().createObjectNode());
    Set<ValidationMessage> wrongType =
        validator.validateWithInlineSchema(
            schema, validator.mapper().createObjectNode().put("itemId", 10001));

    assertThat(ok).isEmpty();
    assertThat(missing).isNotEmpty();
    assertThat(wrongType).isNotEmpty();
  }

  @Test
  void readClasspathJsonFailsClearlyWhenMissing() {
    assertThatThrownBy(() -> validator.readClasspathJson("contracts/nope.json"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope.json");
  }
}
