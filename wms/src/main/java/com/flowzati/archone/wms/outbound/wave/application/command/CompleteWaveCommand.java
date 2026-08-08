package com.flowzati.archone.wms.outbound.wave.application.command;

import java.time.Instant;
import java.util.UUID;

public record CompleteWaveCommand(UUID waveId, Instant completedAt) {

  public CompleteWaveCommand {
    if (waveId == null || completedAt == null) {
      throw new IllegalArgumentException("Complete Wave requires Wave ID and completion time");
    }
  }
}
