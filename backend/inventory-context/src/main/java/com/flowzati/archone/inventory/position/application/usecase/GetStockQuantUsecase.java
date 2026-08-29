package com.flowzati.archone.inventory.position.application.usecase;

import com.flowzati.archone.inventory.position.application.StockQuantView;
import com.flowzati.archone.inventory.position.application.store.StockQuantViewStore;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetStockQuantUsecase {

    private final StockQuantViewStore stockQuantViewStore;

    public GetStockQuantUsecase(StockQuantViewStore stockQuantViewStore) {
        this.stockQuantViewStore = stockQuantViewStore;
    }

    /**
     * 某貨主在某個內部位置手上的所有批，依 SKU、效期、入庫日與 quant id 排序——**含已過期的**。
     *
     * <p>過期的批要留在結果裡並標記，不能靜默略過：「有 100 件但一件都出不了」與
     * 「什麼都沒有」在畫面上必須分得出來，因為前者要報廢、後者要進貨。
     *
     * <p><b>一批都沒有時回 immutable 空清單，不丟例外。</b>現在查的是「這個倉放了什麼」，而
     * 「什麼都沒放」是正常答案——那正是一個新倉剛上線時的狀態，也正是最需要看它的時候。
     *
     * <p><b>不驗貨主與倉的指派關係。</b>那個關係由 catalog 掌管，而庫存這一側對主檔零依賴、連
     * 外鍵都沒有；未指派的組合本來就查不到任何批，回空與回 404 對呼叫端的下一步沒有差別。
     */
    public List<StockQuantView> getBatchesInLocation(UUID ownerId, UUID locationId) {
        return List.copyOf(stockQuantViewStore.findBatchesInLocation(ownerId, locationId));
    }
}
