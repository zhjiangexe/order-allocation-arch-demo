package com.flowzati.archone.inventory.warehouse.domain.repository;

import com.flowzati.archone.inventory.warehouse.domain.aggregate.StockLocation;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface StockLocationRepository {

    void save(StockLocation location);

    /** 依 code 排列某設施可保存庫存的全部內部位置；設施不存在或尚未設定時回空清單。 */
    List<StockLocation> findInternalByFacilityId(UUID facilityId);

    /**
     * 反方向：這個位置屬於哪個設施。
     *
     * <p>建立出庫單與從 waiting move 重建需求時要用。
     */
    Optional<StockLocation> findById(UUID locationId);
}
