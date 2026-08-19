package com.flowzati.archone.stock.allocation.infrastructure.configuration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Final-cutover single-writer gate. This binary contains only the demand committer and refuses to
 * start if an instance is accidentally configured for the retired legacy movement writer.
 */
@Component
public class AllocationWriterModeGate {

  public static final String DEMAND_MODE = "demand";
  private final String mode;

  public AllocationWriterModeGate(
      @Value("${archone.allocation.writer-mode:demand}") String configuredMode) {
    this.mode = configuredMode == null ? "" : configuredMode.trim().toLowerCase();
    if (!DEMAND_MODE.equals(mode)) {
      throw new IllegalStateException(
          "This release only supports archone.allocation.writer-mode=demand; "
              + "pause and drain allocation consumers before changing writer releases");
    }
  }

  public String mode() {
    return mode;
  }
}
