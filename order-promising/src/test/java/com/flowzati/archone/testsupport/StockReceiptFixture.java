package com.flowzati.archone.testsupport;

import com.flowzati.archone.stock.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.stock.application.usecase.ConfirmStockReceiptUsecase;
import com.flowzati.archone.stock.domain.model.StockFixtures;

/** 整合測試直接驅動本地同步收貨邊界，不再偽造外部 availability integration event。 */
public final class StockReceiptFixture {

  private StockReceiptFixture() {
  }

  public static void confirm(
      ConfirmStockReceiptUsecase usecase, String sku, int quantity) {
    usecase.execute(new ConfirmStockReceiptCommand(
        OrderFixtures.OWNER_ID,
        OrderFixtures.FACILITY_ID,
        OrderFixtures.LOCATION_ID,
        sku,
        StockFixtures.ARRIVED_ON,
        StockFixtures.EXPIRES_ON,
        quantity));
  }
}
