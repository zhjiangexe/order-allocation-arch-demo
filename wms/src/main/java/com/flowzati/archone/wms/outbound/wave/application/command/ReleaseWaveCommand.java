package com.flowzati.archone.wms.outbound.wave.application.command;

import java.time.Instant;
import java.util.UUID;

public record ReleaseWaveCommand(UUID waveId, Instant releasedAt) {

    public ReleaseWaveCommand {
        if (waveId == null || releasedAt == null) {
            throw new IllegalArgumentException("Release Wave requires Wave ID and release time");
        }
    }
}
