package com.sparkrooter.registry.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sparkrooter.contracts.ContractViolationException;
import com.sparkrooter.contracts.model.ToolManifest;
import com.sparkrooter.registry.api.ConfirmationCoveragePolicy;
import com.sparkrooter.registry.domain.ToolVersionConflictException;
import com.sparkrooter.registry.infra.InMemoryToolRegistryRepository;
import com.sparkrooter.registry.support.Manifests;
import org.junit.jupiter.api.Test;

/** 注册用例：入站 Manifest 先过 tool-manifest 契约；同 toolId@version 二次注册冲突；成功后可查回。 */
final class RegisterToolUseCaseTest {

  private final InMemoryToolRegistryRepository repo = new InMemoryToolRegistryRepository();
  private final RegisterToolUseCase useCase =
      new RegisterToolUseCase(repo, Manifests.VALIDATOR, ConfirmationCoveragePolicy.permitAll());

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

  /**
   * 注册用例必须<b>真的</b>调用覆盖策略。
   *
   * <p>自证时发现：把 {@code coverage.check()} 从 execute() 删掉，原有全部测试仍绿——策略与用例之间
   * 的接线没人守。策略本身的单测（RegistryConfirmationCoverageTest）证明不了它被调用过。
   */
  @Test
  void executeInvokesCoveragePolicyBeforeWriting() {
    java.util.concurrent.atomic.AtomicInteger calls =
        new java.util.concurrent.atomic.AtomicInteger();
    ConfirmationCoveragePolicy counting = manifest -> calls.incrementAndGet();
    RegisterToolUseCase uc = new RegisterToolUseCase(repo, Manifests.VALIDATOR, counting);

    uc.execute(Manifests.exampleJson());

    assertThat(calls).as("注册路径必须经过覆盖策略").hasValue(1);
  }

  /** 策略拒绝时不得写入仓库（fail-closed，而不是"记下来再说"）。 */
  @Test
  void rejectedByCoverageIsNotPersisted() {
    ConfirmationCoveragePolicy denying =
        manifest -> {
          throw new ConfirmationCoveragePolicy.NotCovered("no recheck");
        };
    RegisterToolUseCase uc = new RegisterToolUseCase(repo, Manifests.VALIDATOR, denying);

    assertThatThrownBy(() -> uc.execute(Manifests.exampleJson()))
        .isInstanceOf(ConfirmationCoveragePolicy.NotCovered.class);
    assertThat(repo.findVersions("refund.eligibility.check")).isEmpty();
  }
}
