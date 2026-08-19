package com.flowzati.archone.inventory.balance.application.usecase;

import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class GetStockQuantUsecase {

    private final StockQuantRepository stockQuantRepository;

    public GetStockQuantUsecase(StockQuantRepository stockQuantRepository) {
        this.stockQuantRepository = stockQuantRepository;
    }

    /**
     * 某貨主在某個內部位置手上的所有批，依 SKU 分組——**含已過期的**。
     *
     * <p>過期的批要留在結果裡並標記，不能靜默略過：「有 100 件但一件都出不了」與
     * 「什麼都沒有」在畫面上必須分得出來，因為前者要報廢、後者要進貨。
     *
     * <p><b>一批都沒有時回空的分組，不丟例外。</b>改動前查的是「某個 SKU 的批」，查無代表使用
     * 者問了一個不存在的組合，該回 404；現在查的是「這個倉放了什麼」，而「什麼都沒放」是正常
     * 答案——那正是一個新倉剛上線時的狀態，也正是最需要看它的時候。
     *
     * <p><b>不驗貨主與倉的指派關係。</b>那個關係由 catalog 掌管，而庫存這一側對主檔零依賴、連
     * 外鍵都沒有；為了回一個更精確的錯誤而讓 allocation 開始讀 catalog，代價遠大於收穫。未指派
     * 的組合本來就查不到任何批，回空與回 404 對呼叫端的下一步沒有差別。
     */
    public Map<String, List<StockQuant>> getBatchesInLocation(UUID ownerId, UUID locationId) {
        return stockQuantRepository.findBatchesInLocation(ownerId, locationId);
    }
}
