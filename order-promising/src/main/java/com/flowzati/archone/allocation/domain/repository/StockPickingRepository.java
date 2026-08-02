package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockPicking;
import java.util.Collection;
import java.util.List;
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

  void save(StockPicking picking);

  /** 一張訂單的作業單。取消時用來找出要取消哪些搬運。 */
  List<StockPicking> findByOrderId(UUID orderId);

  /**
   * 依識別碼取單據。
   *
   * <p>喚醒佇列時用：手上是一批搬運，而它們只帶得動 {@code pickingId}——要發出「這張單配好
   * 了」的事實得先知道是哪張訂單。
   *
   * <p>回的是單據本身而不是 {@code Map<pickingId, orderId>}。曾經是後者，因為那時
   * {@link StockPicking} 沒有 {@code orderId} 欄位，讀出來就丟掉了——一個特化的方法只為了把
   * 型別自己扔掉的東西撈回來。
   */
  List<StockPicking> findByIds(Collection<UUID> pickingIds);
}
