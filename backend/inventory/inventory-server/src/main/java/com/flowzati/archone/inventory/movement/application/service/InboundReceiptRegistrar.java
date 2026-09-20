package com.flowzati.archone.inventory.movement.application.service;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.location.application.store.StockLocationStore;
import com.flowzati.archone.inventory.location.domain.entity.StockLocation;
import com.flowzati.archone.inventory.movement.application.store.StockMoveStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationStore;
import com.flowzati.archone.inventory.movement.application.store.StockOperationTypeStore;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 登記一段尚未完成的 inbound receipt execution。
 *
 * <p><b>它不配貨，也不增加 on-hand。</b>這裡只建立 supply-only inbound operation 與 move；
 * stock-consuming outbound execution 一律由 movement-group registration transaction 建立。
 *
 * <p><b>operation 是這兩條作業流程的必要分組，不是 {@link StockMove} 型別的全域
 * 不變式。</b>Outbound source registration 會讓同一個 allocation unit 的 moves 共用 operation；但通用
 * move 仍可沒有 operation，
 * 例如未來的盤點調整。因此不需要一個強制所有 move 都造 operation 的 Factory。
 */
@Component
public class InboundReceiptRegistrar {

    private final StockLocationStore stockLocationStore;
    private final StockOperationTypeStore stockOperationTypeStore;
    private final StockOperationStore stockOperationStore;
    private final StockMoveStore stockMoveStore;

    public InboundReceiptRegistrar(
            StockLocationStore stockLocationStore,
            StockOperationTypeStore stockOperationTypeStore,
            StockOperationStore stockOperationStore,
            StockMoveStore stockMoveStore) {
        this.stockLocationStore = stockLocationStore;
        this.stockOperationTypeStore = stockOperationTypeStore;
        this.stockOperationStore = stockOperationStore;
        this.stockMoveStore = stockMoveStore;
    }

    /**
     * 為一段式收貨建立一張入庫 operation 與一段搬運：供應商位置 → 呼叫者選定的內部位置。
     *
     * <p>operation 不帶 order、move 不帶 order line，因為這是 stock context 的收貨作業，不是
     * outbound order demand。作業類型提供預設來源；目的地使用 {@code locationId}，不可被
     * {@link StockOperationType#defaultToLocationId()} 覆蓋。這一步只記錄待完成的 warehouse execution，
     * 不直接改庫存。
     */
    public List<StockMove> register(
            UUID facilityId, UUID ownerId, UUID locationId, String skuCode, int quantity, Instant now) {
        StockOperationType type = operationTypeFor(facilityId, locationId, StockOperationDirection.INBOUND);

        UUID stockOperationId = IdGenerator.nextId();
        stockOperationStore.save(StockOperation.confirmedInbound(
                stockOperationId, type.id(), ownerId, type.defaultFromLocationId(), locationId));

        return stockMoveStore.saveAll(List.of(StockMove.confirmed(
                IdGenerator.nextId(),
                stockOperationId,
                ownerId,
                skuCode,
                type.defaultFromLocationId(),
                locationId,
                quantity,
                now)));
    }

    /**
     * 位置 → Facility → 該方向的作業類型。
     *
     * <p>入口同時傳入 Facility 與實際操作位置：先確認位置存在且屬於該
     * Facility，再以 Facility 與方向解析作業類型。這避免需求的位置與作業類型來自
     * 不同 Facility，卻仍建出一張起點錯誤的搬運。
     */
    private StockOperationType operationTypeFor(UUID facilityId, UUID locationId, StockOperationDirection direction) {
        StockLocation location = stockLocationStore
                .findById(locationId)
                .orElseThrow(() -> new IllegalStateException("Stock location " + locationId + " no longer exists"));
        if (!facilityId.equals(location.getFacilityId())) {
            throw new IllegalArgumentException(
                    "Stock location " + locationId + " does not belong to facility " + facilityId);
        }

        return stockOperationTypeStore
                .find(facilityId, direction)
                .orElseThrow(() -> new IllegalStateException(
                        "Facility " + facilityId + " has no " + direction.name().toLowerCase() + " operation type"));
    }
}
