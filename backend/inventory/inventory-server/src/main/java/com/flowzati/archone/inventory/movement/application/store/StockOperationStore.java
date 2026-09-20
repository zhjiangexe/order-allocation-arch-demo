package com.flowzati.archone.inventory.movement.application.store;

import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Inventory movement operation group Application Store。
 *
 * <p><b>這個 group 不是搬運的聚合根，也不是 WMS task</b>，它是搬運的 policy 分組。Odoo 19 的結構在這一點上很明確：狀態由
 * 底下的搬運算上來、四個動作全定義在搬運那一層、搬運甚至會在單據之間搬家（欠交單就是把
 * 未完成的搬運寫到新的單據上）。因此這裡與 {@code StockMoveStore} 是兩個介面，而不是
 * 一個「聚合根的 repository」。因此這個 Application port 使用 Store vocabulary。
 */
public interface StockOperationStore {

    Optional<StockOperation> findById(UUID stockOperationId);

    Optional<StockOperation> findBySource(StockOperationSource source);

    /** 依識別碼載入 inbound operation groups。 */
    List<StockOperation> findByIds(Collection<UUID> stockOperationIds);

    void save(StockOperation operation);

    Optional<StockOperation> lockById(UUID stockOperationId);
}
