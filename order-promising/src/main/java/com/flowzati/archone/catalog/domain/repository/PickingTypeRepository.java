package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.model.PickingDirection;
import com.flowzati.archone.catalog.domain.model.PickingType;
import java.util.Optional;
import java.util.UUID;

public interface PickingTypeRepository {

  void save(PickingType pickingType);

  /**
   * 某個倉的某個方向的作業類型。
   *
   * <p>一個倉的一個方向只有一種類型（資料庫的 {@code uq_stock_picking_types_warehouse_code}
   * 保證），因此回單一值。那條約束不在時，這個方法的型別就是在說謊。
   *
   * <p>查無回空而不拋錯，與位置的解析同一個判準：呼叫端要的處置不同——建立出庫單需要一個
   * 明確的失敗，而診斷查詢只要看得到「這個倉沒有設出庫類型」。
   */
  Optional<PickingType> find(UUID warehouseId, PickingDirection code);
}
