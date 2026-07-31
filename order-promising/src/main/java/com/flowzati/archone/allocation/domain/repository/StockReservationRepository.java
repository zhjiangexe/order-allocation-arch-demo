package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockReservation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface StockReservationRepository {

  void save(StockReservation reservation);


  /**
   * 一張單目前所有有效的預留。
   *
   * <p>取消要釋放的是整張單——一條行跨三批就有三筆預留，只放其中一筆會讓其餘批的量永遠鎖著，
   * 而且不會有任何錯誤浮現，庫存看起來只是莫名其妙少了一些。
   *
   * <p>以 {@code order_id} 查而不是先取行的識別碼再查：後者要 allocation 去讀 ordering 的
   * 訂單，而「這張單有哪些預留」是 allocation 自己的資料，它自己回答得了。
   */
  List<StockReservation> findActiveByOrderId(UUID orderId);
}
