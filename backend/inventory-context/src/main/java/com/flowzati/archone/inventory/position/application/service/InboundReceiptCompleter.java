package com.flowzati.archone.inventory.position.application.service;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.location.application.store.StockLocationStore;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.location.domain.valueobject.LocationUsageType;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.position.application.policy.StockWriteOrder;
import com.flowzati.archone.inventory.position.application.store.StockQuantStore;
import com.flowzati.archone.inventory.position.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.position.domain.valueobject.ReceivingBatchIdentity;
import com.flowzati.archone.inventory.reservation.application.store.StockMoveLineStore;
import com.flowzati.archone.inventory.reservation.domain.entity.StockMoveLine;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 完成已記錄的 inbound movement，建立 move line 並據此增加實體庫存。
 *
 * <p>這是 application component，不是 transaction owner；呼叫端把 inbound 記錄、完成與
 * availability fact 包在同一個 transaction。Outbound waiting-move allocation 在提交後另開交易。
 */
@Component
public class InboundReceiptCompleter {

    private final StockMoveStore stockMoveStore;
    private final StockMoveLineStore stockMoveLineStore;
    private final StockOperationStore stockOperationStore;
    private final StockQuantStore stockQuantStore;
    private final StockLocationStore stockLocationStore;

    public InboundReceiptCompleter(
            StockMoveStore stockMoveStore,
            StockMoveLineStore stockMoveLineStore,
            StockOperationStore stockOperationStore,
            StockQuantStore stockQuantStore,
            StockLocationStore stockLocationStore) {
        this.stockMoveStore = stockMoveStore;
        this.stockMoveLineStore = stockMoveLineStore;
        this.stockOperationStore = stockOperationStore;
        this.stockQuantStore = stockQuantStore;
        this.stockLocationStore = stockLocationStore;
    }

    public void complete(List<StockMove> moves, ReceivingBatchIdentity batchIdentity, Instant now) {
        if (moves == null || moves.isEmpty()) {
            throw new IllegalArgumentException("At least one stock move is required");
        }

        Map<UUID, LocationUsageType> locationUsages = loadRequiredLocationUsages(moves);
        moves.forEach(move -> requireIncoming(move, locationUsages));
        List<StockOperation> operations = loadRequiredOperations(moves);
        Map<ReceivingBatchKey, StockQuant> receivingQuants = resolveReceivingQuants(moves, batchIdentity);

        List<StockMoveLine> lines = completeMovements(moves, receivingQuants, batchIdentity, now);
        completeOperations(operations);
        persistCompletion(receivingQuants.values(), moves, lines, operations);
    }

    private Map<UUID, LocationUsageType> loadRequiredLocationUsages(List<StockMove> moves) {
        Set<UUID> locationIds = new LinkedHashSet<>();
        for (StockMove move : moves) {
            locationIds.add(move.getFromLocationId());
            locationIds.add(move.getToLocationId());
        }

        Map<UUID, LocationUsageType> usagesById = new LinkedHashMap<>();
        for (UUID locationId : locationIds) {
            usagesById.put(locationId, usageOf(locationId));
        }
        return usagesById;
    }

    private void requireIncoming(StockMove move, Map<UUID, LocationUsageType> locationUsages) {
        if (locationUsages.get(move.getFromLocationId()) == LocationUsageType.INTERNAL) {
            throw new IllegalStateException("Completing an outgoing movement is not implemented until shipping exists");
        }
        if (locationUsages.get(move.getToLocationId()) != LocationUsageType.INTERNAL) {
            throw new IllegalStateException(
                    "A completed inbound movement must end in an internal location, was " + move.getToLocationId());
        }
    }

    private List<StockOperation> loadRequiredOperations(List<StockMove> moves) {
        Set<UUID> stockOperationIds =
                moves.stream().map(StockMove::getStockOperationId).collect(Collectors.toCollection(LinkedHashSet::new));
        if (stockOperationIds.contains(null)) {
            throw new IllegalStateException("A completed inbound movement must belong to a operation");
        }

        List<StockOperation> operations = stockOperationStore.findByIds(stockOperationIds);
        if (operations.size() != stockOperationIds.size()) {
            Set<UUID> found = operations.stream().map(StockOperation::id).collect(Collectors.toSet());
            Set<UUID> missing = new LinkedHashSet<>(stockOperationIds);
            missing.removeAll(found);
            throw new IllegalStateException("Stock operations no longer exist: " + missing);
        }
        return operations;
    }

    private Map<ReceivingBatchKey, StockQuant> resolveReceivingQuants(
            List<StockMove> moves, ReceivingBatchIdentity batchIdentity) {
        Map<ReceivingBatchKey, StockQuant> quantsByIdentity = new LinkedHashMap<>();
        for (StockMove move : moves) {
            ReceivingBatchKey key = ReceivingBatchKey.from(move, batchIdentity);
            quantsByIdentity.computeIfAbsent(key, this::quantFor);
        }
        return quantsByIdentity;
    }

    private StockQuant quantFor(ReceivingBatchKey key) {
        return stockQuantStore
                .findByIdentity(key.ownerId(), key.locationId(), key.skuCode(), key.inDate(), key.expiryDate())
                .orElseGet(() -> new StockQuant(
                        IdGenerator.nextId(),
                        key.ownerId(),
                        key.locationId(),
                        key.skuCode(),
                        key.inDate(),
                        key.expiryDate(),
                        0,
                        0,
                        null));
    }

    private List<StockMoveLine> completeMovements(
            List<StockMove> moves,
            Map<ReceivingBatchKey, StockQuant> receivingQuants,
            ReceivingBatchIdentity batchIdentity,
            Instant now) {
        List<StockMoveLine> lines = new ArrayList<>();
        for (StockMove move : moves) {
            ReceivingBatchKey key = ReceivingBatchKey.from(move, batchIdentity);
            StockQuant quant = receivingQuants.get(key);
            StockMoveLine line =
                    new StockMoveLine(IdGenerator.nextId(), move.getId(), quant.getId(), move.getDemandQuantity());
            lines.add(line);

            move.assign(now);
            move.complete(now);
            quant.receive(line);
        }
        return List.copyOf(lines);
    }

    private void completeOperations(List<StockOperation> operations) {
        for (StockOperation operation : operations) {
            operation.assign();
            operation.complete();
        }
    }

    private void persistCompletion(
            Collection<StockQuant> receivingQuants,
            List<StockMove> moves,
            List<StockMoveLine> lines,
            List<StockOperation> operations) {
        receivingQuants.stream().sorted(StockWriteOrder.BY_GLOBAL_ORDER).forEach(stockQuantStore::save);
        stockMoveStore.saveAll(moves);
        stockMoveLineStore.saveAll(lines);
        operations.forEach(stockOperationStore::save);
    }

    private LocationUsageType usageOf(UUID locationId) {
        return stockLocationStore
                .findById(locationId)
                .map(StockLocation::getUsage)
                .orElseThrow(() -> new IllegalStateException("Stock location " + locationId + " no longer exists"));
    }

    private record ReceivingBatchKey(
            UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate) {

        private static ReceivingBatchKey from(StockMove move, ReceivingBatchIdentity batchIdentity) {
            return new ReceivingBatchKey(
                    move.getOwnerId(),
                    move.getToLocationId(),
                    move.getSkuCode(),
                    batchIdentity.inDate(),
                    batchIdentity.expiryDate());
        }
    }
}
