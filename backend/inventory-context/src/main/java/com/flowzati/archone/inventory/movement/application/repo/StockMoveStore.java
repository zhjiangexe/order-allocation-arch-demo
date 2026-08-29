package com.flowzati.archone.inventory.movement.application.repo;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Movement 對 canonical Stock Move 的持久化邊界。Reservation detail 由自己的 port 擁有。 */
public interface StockMoveStore {

    List<StockMove> findOrderedByStockOperationId(UUID stockOperationId);

    List<StockMove> findByIds(Collection<UUID> moveIds);

    void save(StockMove move);

    /**
     * 寫入這些搬運，並**回傳寫入後的它們**。
     *
     * <p>回傳值不是方便，是必要的：同一個交易裡「建立、接著鎖定」會對同一列寫兩次，而第二次
     * 必須知道第一次之後的版號才會是更新而不是新增。傳入的物件是剛建構出來的，版號為空——
     * 拿它去寫第二次，持久層會當成一列全新的資料。
     */
    List<StockMove> saveAll(Collection<StockMove> moves);

    /** Locks the complete group in the canonical write order: move id ascending. */
    List<StockMove> lockByStockOperationIdInIdOrder(UUID stockOperationId);
}
