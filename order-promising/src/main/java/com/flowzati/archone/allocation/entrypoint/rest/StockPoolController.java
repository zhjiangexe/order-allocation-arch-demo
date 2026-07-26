package com.flowzati.archone.allocation.entrypoint.rest;

import com.flowzati.archone.allocation.application.usecase.GetStockPoolUsecase;
import java.util.NoSuchElementException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * allocation 的第一支 REST entrypoint，且刻意只有唯讀查詢——命令仍然只從 Kafka
 * entrypoint 進入，配置決策不開 HTTP 入口。
 */
@RestController
@RequestMapping("/stock-pool")
public class StockPoolController {

  private final GetStockPoolUsecase getStockPoolUsecase;

  public StockPoolController(GetStockPoolUsecase getStockPoolUsecase) {
    this.getStockPoolUsecase = getStockPoolUsecase;
  }

  @GetMapping("/{sku}")
  public StockPoolResponse getStockPool(@PathVariable String sku) {
    return StockPoolResponse.from(getStockPoolUsecase.getBySku(sku));
  }

  /**
   * 「找不到」本身由 {@link GetStockPoolUsecase} 判斷並丟出 JDK 原生的
   * {@link NoSuchElementException}，是否轉成 404 則是 HTTP 層的決定。這個 controller 目前
   * 只有一個唯讀端點，沒有其他路徑會拋出語意不是「找不到」的同型別例外。
   */
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<String> handleNotFound(NoSuchElementException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
  }
}
