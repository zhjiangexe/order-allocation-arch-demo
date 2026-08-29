package com.flowzati.archone.inventory.movement.application.command;

import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Source-facing execution proof; the application resolves its moves to one canonical operation. */
public record CompleteSourceStockMovementsCommand(
        StockOperationSource source, List<UUID> moveIds, Instant completedAt) {

    public CompleteSourceStockMovementsCommand {
        if (source == null || moveIds == null || completedAt == null) {
            throw new IllegalArgumentException("Completion requires source, movement identities and time");
        }
        moveIds = List.copyOf(moveIds);
        if (moveIds.isEmpty() || moveIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Completion requires at least one movement identity");
        }
        if (new HashSet<>(moveIds).size() != moveIds.size()) {
            throw new IllegalArgumentException("Completion movement identities must be unique");
        }
    }
}
