package com.flowzati.archone.logisticsdata.entrypoint.rest.response;

import com.flowzati.archone.logisticsdata.domain.aggregate.Sku;
import java.util.UUID;

/**
 * 款之下的規格。刻意不帶溫層——溫層屬款層級，在這裡重複一份會讓「同款兩種溫層」在契約上
 * 重新變成可表達的東西，而資料模型正是為了排除它才拆成兩層。
 */
public record SkuResponse(
        UUID skuId, UUID ownerId, String skuCode, String productCode, String specName, int weightGram) {

    public static SkuResponse from(Sku sku) {
        return new SkuResponse(
                sku.getId(),
                sku.getOwnerId(),
                sku.getSkuCode(),
                sku.getProductCode(),
                sku.getSpecName(),
                sku.getWeightGram());
    }
}
