package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.domain.model.LocationUsage;
import com.flowzati.archone.catalog.infrastructure.entity.StockLocationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockLocationRepository extends JpaRepository<StockLocationEntity, UUID> {

  /**
   * 回 {@code Optional} 而非 {@code List}：一個倉最多一個內部位置，這是資料庫的
   * {@code uq_stock_locations_internal_per_warehouse} 保證的。用途一併帶進條件，
   * 是為了讓這個方法在日後一倉多位置時仍然只挑得到內部位置。
   */
  Optional<StockLocationEntity> findByWarehouseIdAndUsage(UUID warehouseId, LocationUsage usage);
}
