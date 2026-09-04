package com.strato.contracts;

import com.networknt.schema.ValidationMessage;
import java.util.Collections;
import java.util.Set;

/** 跨边界数据不符合契约。message 只含契约名与 instancePath / 原因，不含数据原文。 */
public class ContractViolationException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final String contractName;
  private final transient Set<ValidationMessage> violations;

  public ContractViolationException(String contractName, Set<ValidationMessage> violations) {
    super(
        "contract "
            + contractName
            + " violated: "
            + violations.stream()
                .map(v -> v.getInstanceLocation() + " " + v.getMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("(no details)"));
    this.contractName = contractName;
    this.violations = Collections.unmodifiableSet(violations);
  }

  public String contractName() {
    return contractName;
  }

  public Set<ValidationMessage> violations() {
    return violations;
  }
}
