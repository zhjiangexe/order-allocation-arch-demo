package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockPicking;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 倉庫作業單。
 *
 * <p><b>單據不是搬運的聚合根</b>，它是搬運的分組。Odoo 19 的結構在這一點上很明確：狀態由
 * 底下的搬運算上來、四個動作全定義在搬運那一層、搬運甚至會在單據之間搬家（欠交單就是把
 * 未完成的搬運寫到新的單據上）。因此這裡與 {@code StockMoveRepository} 是兩個介面，而不是
 * 一個「聚合根的 repository」。
 */
public interface StockPickingRepository {

  void save(StockPicking picking, UUID orderId);

  /** 一張訂單的作業單。取消時用來找出要取消哪些搬運。 */
  List<StockPicking> findByOrderId(UUID orderId);

  /** 這些單據各自服務哪張訂單——配到之後要發帶 orderId 的事件時用。 */
  Map<UUID, UUID> findOrderIdsByIds(Collection<UUID> pickingIds);
}
