package com.flowzati.archone.stock.entrypoint.rest;

import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.usecase.GetStockPoolUsecase;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * allocation 的第一支 REST entrypoint，且刻意只有唯讀查詢——命令仍然只從 Kafka
 * entrypoint 進入，配置決策不開 HTTP 入口。
 */
@RestController
@RequestMapping("/stock-pool")
public class StockPoolController {

  private final GetStockPoolUsecase getStockPoolUsecase;
  private final AppClock appClock;

  public StockPoolController(GetStockPoolUsecase getStockPoolUsecase, AppClock appClock) {
    this.getStockPoolUsecase = getStockPoolUsecase;
    this.appClock = appClock;
  }

  /**
   * 這個貨主在這個倉手上的全部批，依 SKU 分組。
   *
   * <p>兩個參數都是必要的，而且都不是選用篩選。少了 {@code ownerId}，回應會把兩個貨主的貨
   * 混在一起——SKU 代碼由貨主自訂、跨貨主撞號。少了 {@code locationId}，一個多庫位設施就
   * 無法回答實際查的是哪一個庫存端點。
   *
   * <p><b>一批都沒有時回 200 與空清單，不是 404。</b>「這個倉什麼都沒放」是正常答案，不是
   * 問了不存在的東西——而那正是新倉上線時的狀態，也正是最需要打開它的時候。
   */
  @GetMapping
  public StockPoolResponse getStockInLocation(
      @RequestParam UUID ownerId,
      @RequestParam UUID locationId
  ) {
    return StockPoolResponse.from(
        getStockPoolUsecase.getBatchesInLocation(ownerId, locationId),
        appClock.today());
  }
}
