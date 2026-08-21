package com.flowzati.archone.inventory.warehouse.domain.repository;

import com.flowzati.archone.inventory.warehouse.domain.aggregate.PickingDefinition;
import com.flowzati.archone.inventory.warehouse.domain.type.PickingDirection;
import java.util.Optional;
import java.util.UUID;

public interface PickingTypeRepository {

    void save(PickingDefinition pickingType);

    /**
     * 某個物流設施的某個方向的作業類型。
     *
     * <p>一個設施的一個方向只有一種類型（資料庫的 {@code uq_stock_picking_types_facility_code}
     * 保證），因此回單一值。那條約束不在時，這個方法的型別就是在說謊。
     *
     * <p>查無回空而不拋錯，與位置的解析同一個判準：呼叫端要的處置不同——建立出庫單需要一個
     * 明確的失敗，而診斷查詢只要看得到「這個設施沒有設出庫類型」。
     */
    Optional<PickingDefinition> find(UUID facilityId, PickingDirection code);
}
