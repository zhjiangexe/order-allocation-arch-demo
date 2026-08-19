package com.flowzati.archone.inventory.movement.domain.repository;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * 搬運與它的明細。
 *
 * <p><b>明細沒有自己的 repository，因為它沒有獨立的生命週期</b>——本系統的
 * {@code stock_move_lines.move_id} 是 NOT NULL，一條明細只在某段搬運被配到時整批建立、在它被
 * 取消時整批刪除。
 *
 * <p>（Odoo 的 {@code stock.move.line.move_id} 可空，明細在那裡是一等實體、有自己的選單與
 * 分析畫面。我們刻意收緊：沒有盤點調整那種「有明細卻沒有搬運」的流程。）
 *
 * <p><b>沒有「更新明細」的方法。</b>明細只有建立與刪除兩種變化。介面上不給更新，是為了讓
 * 「把它標成已釋放」這條路在型別層就走不通——一條被釋放的明細不表達任何事實。
 */
public interface StockMoveRepository {

    void save(StockMove move);

    /**
     * 寫入這些搬運，並**回傳寫入後的它們**。
     *
     * <p>回傳值不是方便，是必要的：同一個交易裡「建立、接著鎖定」會對同一列寫兩次，而第二次
     * 必須知道第一次之後的版號才會是更新而不是新增。傳入的物件是剛建構出來的，版號為空——
     * 拿它去寫第二次，持久層會當成一列全新的資料。
     */
    List<StockMove> saveAll(Collection<StockMove> moves);

    void saveLines(Collection<StockMoveLine> lines);

    List<StockMove> findByPickingIds(Collection<UUID> pickingIds);

    List<StockMove> findByOrderLineIds(Collection<UUID> orderLineIds);

    /** All execution movements belonging to one allocation-owned demand. */
    List<StockMove> findByAllocationDemandId(UUID allocationDemandId);

    List<StockMoveLine> findLinesOf(Collection<UUID> moveIds);

    /** 釋放：刪除這些搬運的全部明細。 */
    void deleteLinesOf(Collection<UUID> moveIds);
}
