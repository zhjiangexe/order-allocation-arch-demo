package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {
  private final PlaceOrderUsecase placeOrderUsecase;
  private final GetOrderUsecase getOrderUsecase;

  public OrderController(PlaceOrderUsecase placeOrderUsecase, GetOrderUsecase getOrderUsecase) {
    this.placeOrderUsecase = placeOrderUsecase;
    this.getOrderUsecase = getOrderUsecase;
  }

  @RequestMapping
  public ResponseEntity<?> placeOrder(@RequestParam String sku, @RequestParam Integer quantity) {
    UUID orderId = placeOrderUsecase.placeOrder(sku, quantity);
    return ResponseEntity.ok(orderId);
  }

  @GetMapping("/{orderId}")
  public OrderStatusResponse getOrder(@PathVariable UUID orderId) {
    return OrderStatusResponse.from(getOrderUsecase.getOrder(orderId));
  }

  /**
   * 是否為 404 是 HTTP 轉譯層的決定，「找不到」這件事本身由 {@link GetOrderUsecase} 判斷並丟出
   * JDK 原生的 {@link NoSuchElementException}。這裡直接攔截這個泛用型別而不是自訂例外類型，
   * 是因為目前這個 controller 只有 {@code placeOrder} 跟 {@code getOrder} 兩個 endpoint，前者
   * 沒有任何路徑會拋出這個例外；未來若這個 controller 長出其他也可能拋 `NoSuchElementException`
   * 但語意不是「找不到」的 endpoint，要重新評估這個攔截範圍。
   */
  @ExceptionHandler(NoSuchElementException.class)
  public ResponseEntity<String> handleNotFound(NoSuchElementException exception) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(exception.getMessage());
  }
}
