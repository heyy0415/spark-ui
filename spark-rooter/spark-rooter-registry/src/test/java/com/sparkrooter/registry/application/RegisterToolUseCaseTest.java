package com.sparkrooter.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.ContractViolationException;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.domain.ToolVersionConflictException;
import com.sparkrooter.registry.infra.InMemoryToolRegistryRepository;
import com.sparkrooter.registry.support.Manifests;
import org.junit.jupiter.api.Test;

/** 注册用例：入站 Manifest 先过 tool-manifest 契约；同 toolId@version 二次注册冲突；成功后可查回。 */
final class RegisterToolUseCaseTest {

  private final InMemoryToolRegistryRepository repo = new InMemoryToolRegistryRepository();
  private final RegisterToolUseCase useCase = new RegisterToolUseCase(repo, Manifests.VALIDATOR);

  @Test
  void registersContractValidManifest() {
    ToolManifest m = useCase.execute(Manifests.json("demo.item.get", "1.0.0", "demo"));
    assertThat(m.key()).isEqualTo("demo.item.get@1.0.0");
    assertThat(repo.find("demo.item.get", "1.0.0")).isPresent();
  }

  @Test
  void rejectsManifestViolatingContract() {
    ObjectNode bad = Manifests.exampleJson();
    bad.remove("inputSchema");
    assertThatThrownBy(() -> useCase.execute(bad)).isInstanceOf(ContractViolationException.class);
    assertThat(repo.findAll()).isEmpty();
  }

  @Test
  void rejectsHighRiskWithoutConfirmation() {
    // contracts.md §5：high 必须 confirmation=required，由契约 if/then 守护
    ObjectNode bad = Manifests.exampleJson();
    ((ObjectNode) bad.get("risk")).put("level", "high").put("confirmation", "never");
    assertThatThrownBy(() -> useCase.execute(bad)).isInstanceOf(ContractViolationException.class);
  }

  @Test
  void secondRegistrationOfSameVersionConflicts() {
    useCase.execute(Manifests.json("demo.item.get", "1.0.0", "demo"));
    assertThatThrownBy(() -> useCase.execute(Manifests.json("demo.item.get", "1.0.0", "demo")))
        .isInstanceOf(ToolVersionConflictException.class)
        .satisfies(
            e ->
                assertThat(((ToolVersionConflictException) e).key())
                    .isEqualTo("demo.item.get@1.0.0"));
  }
}
