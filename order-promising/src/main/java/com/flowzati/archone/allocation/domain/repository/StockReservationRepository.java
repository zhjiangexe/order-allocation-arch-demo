package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockReservation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockReservationRepository {

  void save(StockReservation reservation);

  /**
   * 這些訂單行目前還有效的預留。
   *
   * <p>回的是清單而不是單一筆：一條行可以跨多批，就有多筆預留。取消一張單必須把它們**全部**
   * 釋放，只釋放第一筆會讓其餘批的量永遠鎖在那裡。
   *
   * <p>參數是行的 id 而不是訂單 id——{@code stock_reservations} 指向 {@code order_lines}，用
   * 訂單查就得 join 到 ordering 的表，那個方向的依賴不該由這一層建立。呼叫端先取得行的 id
   * 再進來。
   */
  List<StockReservation> findActiveByOrderLineIds(Collection<UUID> orderLineIds);
}
