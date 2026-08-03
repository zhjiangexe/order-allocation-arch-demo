package com.flowzati.archone.stock.entrypoint.rest;

import com.flowzati.archone.common.time.AppClock;
import com.flowzati.archone.stock.application.usecase.GetStockPoolUsecase;
import com.flowzati.archone.catalog.domain.model.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;

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
  private final StockLocationRepository stockLocationRepository;
  private final AppClock appClock;

  public StockPoolController(
      GetStockPoolUsecase getStockPoolUsecase,
      StockLocationRepository stockLocationRepository, AppClock appClock) {
    this.getStockPoolUsecase = getStockPoolUsecase;
    this.stockLocationRepository = stockLocationRepository;
    this.appClock = appClock;
  }

  /**
   * 這個貨主在這個倉手上的全部批，依 SKU 分組。
   *
   * <p>兩個參數都是必要的，而且都不是選用篩選。少了 {@code ownerId}，回應會把兩個貨主的貨
   * 混在一起——SKU 代碼由貨主自訂、跨貨主撞號。少了 {@code nodeId}，回的是一個沒有任何一次
   * 配貨能整批取用的池：配貨從不跨倉，每一次都鎖在一個倉裡。
   *
   * <p><b>一批都沒有時回 200 與空清單，不是 404。</b>「這個倉什麼都沒放」是正常答案，不是
   * 問了不存在的東西——而那正是新倉上線時的狀態，也正是最需要打開它的時候。
   */
  @GetMapping
  public StockPoolResponse getStockInWarehouse(
      @RequestParam UUID ownerId,
      @RequestParam UUID nodeId
  ) {
    return StockPoolResponse.from(
        getStockPoolUsecase.getBatchesInLocation(ownerId, internalLocationOf(nodeId)),
        appClock.today());
  }

  /**
   * 倉 → 該倉的內部位置。查詢參數維持 {@code nodeId}——操作台問的是「這個倉放了什麼」，
   * 它不需要認識倉裡的位置編排。
   *
   * <p>倉沒有內部位置時回一個不存在的位置 id，讓查詢自然回空。這與「倉存在但什麼都沒放」
   * 的結果相同，而那本來就是正常答案（見 usecase 的 javadoc）——為了區分兩者而回 404，
   * 會讓一個新倉剛上線時的畫面看起來像壞掉。
   */
  private UUID internalLocationOf(UUID nodeId) {
    return stockLocationRepository.findInternalOf(nodeId)
        .map(StockLocation::getId)
        .orElse(new UUID(0L, 0L));
  }

}
