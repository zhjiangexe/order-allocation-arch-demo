package com.flowzati.archone.inventory.movement.entrypoint.rest;

import com.flowzati.archone.inventory.api.operation.InventoryOperationQueryApi;
import com.flowzati.archone.inventory.api.operation.InventoryOperationView;
import com.flowzati.archone.inventory.movement.application.result.StockMoveLineView;
import com.flowzati.archone.inventory.movement.application.result.StockMoveView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import java.util.UUID;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InventoryOperationQueryRpcRest implements InventoryOperationQueryApi {

    private final StockOperationQueryService queryService;

    public InventoryOperationQueryRpcRest(StockOperationQueryService queryService) {
        this.queryService = queryService;
    }

    @Override
    public InventoryOperationView findPrimaryOrder(UUID orderId) {
        return queryService
                .findPrimaryOrder(orderId)
                .map(InventoryOperationQueryRpcRest::toView)
                .orElse(null);
    }

    private static InventoryOperationView toView(StockOperationView view) {
        return new InventoryOperationView(
                new InventoryOperationView.Source(
                        view.source().type().name(),
                        view.source().sourceId(),
                        view.source().operationUnitKey()),
                new InventoryOperationView.Operation(
                        view.operation().stockOperationId(),
                        view.operation().stockOperationTypeId(),
                        view.operation().direction().name(),
                        view.operation().ownerId(),
                        view.operation().fromLocationId(),
                        view.operation().toLocationId(),
                        view.operation().assignmentPolicy().name(),
                        view.operation().enqueuedAt(),
                        view.operation().dispatchBy(),
                        view.operation().releasePriority(),
                        view.operation().state().name()),
                view.moves().stream()
                        .map(InventoryOperationQueryRpcRest::toMove)
                        .toList());
    }

    private static InventoryOperationView.Move toMove(StockMoveView move) {
        return new InventoryOperationView.Move(
                move.moveId(),
                move.sourceLineId(),
                move.lineSequence(),
                move.skuCode(),
                move.quantity(),
                move.state().name(),
                move.createdAt(),
                move.assignedAt(),
                move.moveLines().stream()
                        .map(InventoryOperationQueryRpcRest::toBatch)
                        .toList());
    }

    private static InventoryOperationView.Batch toBatch(StockMoveLineView line) {
        return new InventoryOperationView.Batch(
                line.stockQuantId(),
                line.locationId(),
                line.skuCode(),
                line.inDate(),
                line.expiryDate(),
                line.quantity());
    }
}
