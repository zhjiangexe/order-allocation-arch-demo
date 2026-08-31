package com.flowzati.archone.inventory.balance.application.store;

import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Application-owned data-access boundary for canonical Stock Quant state. */
public interface StockQuantStore {

    Optional<StockQuant> findById(UUID id);

    /** 一次取得指定批次；回傳結果不保證順序，且不存在的識別碼不會出現在結果中。 */
    List<StockQuant> findByIds(Collection<UUID> ids);

    /**
     * 依五個身分維度取那一列。
     *
     * <p>補貨用它決定是加到既有列還是新開一列——命中就加、沒命中就開。合併規則因此完全由
     * 鍵決定，沒有另一套邏輯要維護，也就沒有另一套邏輯會與鍵不一致。
     */
    Optional<StockQuant> findByIdentity(
            UUID ownerId, UUID locationId, String skuCode, LocalDate inDate, LocalDate expiryDate);

    /** 依全域寫入順序鎖住指定批次；只供同時改 reservation／physical counters 的 transaction 使用。 */
    List<StockQuant> lockByIds(Collection<UUID> ids);

    int save(StockQuant stockQuant);
}
