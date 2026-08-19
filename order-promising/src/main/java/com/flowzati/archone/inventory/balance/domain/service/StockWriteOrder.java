package com.flowzati.archone.inventory.balance.domain.service;

import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import java.util.Comparator;

/**
 * 寫入庫存列的**全域順序**。
 *
 * <p>兩個交易若以相反順序去鎖同一組列，就會互相等待成死鎖。避免的方式是全系統以**同一個全序**
 * 寫入——而「同一個」的意思是只能有一份定義。每個寫入者各自持有一份比較器，三份複本會漂移，
 * 而漂移的症狀是**併發下偶爾死鎖**：最難重現的一類問題，壓測跑不出來，正式環境才偶爾出現。
 *
 * <p>這個型別存在的理由就是那個「只有一份」。它曾經是配貨協調者的私有欄位，那時只有一個寫入
 * 者；現在鎖定與取消各是一個，R7 的完成會是第三個。
 *
 * <p><b>排序鍵現在就寫成跨 SKU 的形式</b>，即使收單目前限定單行、一次配貨只碰一個 SKU——R8
 * 放寬多行之後一次配貨會碰多個 SKU 的多個批，屆時才補上 {@code skuCode} 是死鎖裡最難重現的
 * 一類問題。
 *
 * <p><b>不可依賴集合的自然順序。</b>FEFO 查詢**碰巧**已經是這個順序，但那是查詢的實作細節；
 * 取消與補貨兩條路徑的集合來源完全不同。
 */
public final class StockWriteOrder {

    public static final Comparator<StockQuant> BY_GLOBAL_ORDER = Comparator.comparing(StockQuant::getSkuCode)
            .thenComparing(StockQuant::getExpiryDate)
            .thenComparing(StockQuant::getInDate)
            .thenComparing(StockQuant::getId);

    private StockWriteOrder() {}
}
