package com.flowzati.archone.inventory.movement.application.store;

import com.flowzati.archone.inventory.movement.domain.entity.StockOperationType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import java.util.Optional;
import java.util.UUID;

/** Application-owned data-access boundary for stock operation types. */
public interface StockOperationTypeStore {

    Optional<StockOperationType> findById(UUID stockOperationTypeId);

    /**
     * 某個物流設施的某個方向的作業類型。
     *
     * <p>一個設施的一個方向只有一種類型（資料庫的 {@code uq_stock_operation_types_facility_code}
     * 保證），因此回單一值。那條約束不在時，這個方法的型別就是在說謊。
     */
    Optional<StockOperationType> find(UUID facilityId, StockOperationDirection code);

    void save(StockOperationType stockOperationType);
}
