package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.model.StockLocation;
import java.util.Optional;
import java.util.UUID;

public interface StockLocationRepository {

  void save(StockLocation location);

  /**
   * 解析一個倉的內部位置。
   *
   * <p>倉不存在、或那個倉還沒有內部位置時**回空，不拋例外**——兩個呼叫端要的處置不同：
   * 收單要拒絕整張單，而診斷查詢只要看得到「這個倉沒有位置」。在這一層替他們決定，會讓
   * 其中一個拿到錯的行為。
   *
   * <p>回傳單一值而非清單，是因為資料庫保證了一個倉最多一個內部位置
   * （{@code uq_stock_locations_internal_per_warehouse}）。那條約束不在時，這個方法的
   * 型別就是在說謊。
   */
  Optional<StockLocation> findInternalOf(UUID warehouseId);
}
