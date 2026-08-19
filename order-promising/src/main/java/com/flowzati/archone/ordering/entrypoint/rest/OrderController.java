package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.aggregate.Order;

import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.ListRecentOrdersUsecase;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import org.springframework.dao.DataIntegrityViolationException;
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
    return OrderStatusResponse.from(placeOrderUsecase.placeOrder(toCommand(request)));
  }

  private static PlaceOrderCommand toCommand(PlaceOrderRequest request) {
    if (request.releasePriority() == null) {
      throw new IllegalArgumentException("Release priority is required");
    }
    List<PlaceOrderCommand.Line> lines = request.lines() == null
        ? null
        : request.lines().stream()
            .map(line -> new PlaceOrderCommand.Line(line.skuCode(), line.quantity()))
            .toList();
    return new PlaceOrderCommand(
        request.ownerId(),
        request.externalOrderNo(),
        request.shipToZone(),
        request.shipToAddress(),
        request.promisedDeliveryDate(),
        request.dispatchBy(),
        request.releasePriority(),
        request.facilityId(),
        request.placedAt(),
        lines);
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

  /**
   * 儲存層的完整性拒絕，目前有兩種，而兩種對呼叫方的意義不同：
   *
   * <ul>
   *   <li><strong>上游單號重複</strong>回 {@code 409}——請求本身沒有格式問題，是它與既有
   *       狀態衝突；呼叫方該做的不是修正欄位再送，而是去查那張已經存在的訂單。收單冪等
   *       實作之後，這條路徑會變成回傳既有訂單。在那之前明確報錯，總比靜默建立第二筆好
   *       ——在倉儲場景，重複的訂單是會出兩次貨的實體事故。
   *   <li><strong>訂單行指向該貨主沒有的 SKU</strong>回 {@code 400}——引用了不存在的東西，
   *       那是請求內容的問題。刻意不在應用層預先查主檔換取更漂亮的訊息：那會多一條「檢查
   *       通過但寫入時已被刪除」的競爭路徑，而完整性本來就該由外鍵保證。
   * </ul>
   *
   * <p><strong>為什麼靠 constraint 名稱區分，而不是 Spring 的 {@code DuplicateKeyException}
   * ：</strong>那個子型別只在 {@code JdbcTemplate} 的轉譯路徑上出現。實測經 JPA 寫入時，
   * unique 違反一律轉成 {@code DataIntegrityViolationException} 父型別，訊息裡才帶著
   * constraint 名稱。攔子型別的話那條路徑永遠不會走到。
   *
   * <p>轉譯也不能放進 repository：例外要到 flush 或 commit 才拋，那時已經離開
   * repository 的方法了。
   */
  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<String> handleIntegrityViolation(DataIntegrityViolationException e) {
    if (mentions(e, "uq_orders_owner_external_no")) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body("An order with this owner and external order number already exists");
    }
    return ResponseEntity.badRequest()
        .body("The order references catalog data that does not exist for this owner");
  }

  /** constraint 名稱由本專案的 migration 定義，不隨資料庫版本的措辭改變。 */
  private static boolean mentions(Throwable throwable, String constraintName) {
    for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains(constraintName)) {
        return true;
      }
    }
    return false;
  }
}
