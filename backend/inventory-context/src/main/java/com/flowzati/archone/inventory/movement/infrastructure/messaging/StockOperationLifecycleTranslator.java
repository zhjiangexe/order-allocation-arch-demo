package com.flowzati.archone.inventory.movement.infrastructure.messaging;

import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.inventory.v2.InventoryAggregateTypes;
import com.flowzati.archone.contracts.inventory.v2.StockOperationLifecycleIntegrationEvent;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.application.event.StockOperationCompleted;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleAction;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleChanged;
import com.flowzati.archone.inventory.movement.application.event.StockOperationLifecycleSnapshot;
import com.flowzati.archone.messaging.events.AggregateReference;
import com.flowzati.archone.messaging.events.IntegrationEventPublication;
import com.flowzati.archone.messaging.events.PublicationTarget;

/** Translates a source-neutral Inventory lifecycle fact to the durable stock-operation audit stream. */
public final class StockOperationLifecycleTranslator {

    private StockOperationLifecycleTranslator() {}

    public static IntegrationEventPublication translate(StockOperationLifecycleChanged lifecycleChanged) {
        return translate(lifecycleChanged.action(), lifecycleChanged.snapshot());
    }

    public static IntegrationEventPublication translate(StockOperationCompleted completed) {
        return translate(StockOperationLifecycleAction.COMPLETED, completed.snapshot());
    }

    private static IntegrationEventPublication translate(
            StockOperationLifecycleAction action, StockOperationLifecycleSnapshot snapshot) {
        var source = snapshot.source();
        var event = new StockOperationLifecycleIntegrationEvent(
                IdGenerator.nextId(),
                snapshot.stockOperationId(),
                snapshot.stockOperationTypeId(),
                source.sourceType().name(),
                source.sourceId(),
                source.allocationUnitKey(),
                StockOperationLifecycleIntegrationEvent.LifecycleAction.valueOf(action.name()),
                snapshot.moves().stream()
                        .map(move -> new StockOperationLifecycleIntegrationEvent.MoveSnapshot(
                                move.moveId(),
                                move.sourceLineId(),
                                move.skuCode(),
                                move.quantity(),
                                move.moveLines().stream()
                                        .map(line -> new StockOperationLifecycleIntegrationEvent.BatchSnapshot(
                                                line.stockQuantId(), line.quantity()))
                                        .toList()))
                        .toList(),
                snapshot.occurredAt());
        return new IntegrationEventPublication(
                event,
                new AggregateReference(
                        InventoryAggregateTypes.STOCK_OPERATION,
                        snapshot.stockOperationId().toString()),
                new PublicationTarget(
                        InventoryChannels.STOCK_OPERATION_EVENTS,
                        snapshot.stockOperationId().toString()),
                snapshot.occurredAt());
    }
}
