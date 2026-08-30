package com.flowzati.archone.inventory.reservation.application.store;

import com.flowzati.archone.inventory.reservation.domain.entity.StockMoveLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reservation 對已承諾 Stock Move 明細的唯一持久化邊界。
 *
 * <p>Move Line 沒有獨立於 Stock Move 的生命週期：assignment 整批建立，release 整批刪除。
 * 介面刻意不提供 update，讓「已釋放明細」無法成為第二種持久狀態。
 */
public interface StockMoveLineStore {

    List<StockMoveLine> findByMoveIds(Collection<UUID> stockMoveIds);

    void saveAll(Collection<StockMoveLine> stockMoveLines);

    void deleteByMoveIds(Collection<UUID> stockMoveIds);
}
