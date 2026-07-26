package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.ListRecentOrdersUsecase;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

@RestController
@RequestMapping("/orders")
public class OrderController {
  private static final int DEFAULT_LIMIT = 20;
  private static final int MIN_LIMIT = 1;
  private static final int MAX_LIMIT = 100;

  private final PlaceOrderUsecase placeOrderUsecase;
  private final GetOrderUsecase getOrderUsecase;
  private final ListRecentOrdersUsecase listRecentOrdersUsecase;

  public OrderController(
      PlaceOrderUsecase placeOrderUsecase,
      GetOrderUsecase getOrderUsecase,
      ListRecentOrdersUsecase listRecentOrdersUsecase
  ) {
    this.placeOrderUsecase = placeOrderUsecase;
    this.getOrderUsecase = getOrderUsecase;
    this.listRecentOrdersUsecase = listRecentOrdersUsecase;
  }

  @PostMapping
  public OrderStatusResponse placeOrder(@RequestBody PlaceOrderRequest request) {
    return OrderStatusResponse.from(
        placeOrderUsecase.placeOrder(request.sku(), request.quantity()));
  }

  @GetMapping
  public List<OrderStatusResponse> listRecentOrders(
      @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit
  ) {
    if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
      throw new IllegalArgumentException(
          "limit must be between " + MIN_LIMIT + " and " + MAX_LIMIT + ", but was " + limit);
    }
    return listRecentOrdersUsecase.listRecent(limit).stream()
        .map(OrderStatusResponse::from)
        .toList();
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

  /**
   * 兩條路徑會走到這裡，兩者都是「呼叫方給了不合法的輸入」，因此都該是 400：
   * {@code listRecentOrders} 的 limit 超出範圍，以及 {@code placeOrder} 送出被 Order
   * aggregate 拒絕的 SKU 或數量。
   *
   * <p>limit 超出範圍刻意回 400 而不是靜默截斷成上限——靜默截斷會讓呼叫方無法分辨
   * 「只有這麼多筆」與「被截斷了」。
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(exception.getMessage());
  }
}
