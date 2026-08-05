package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import com.flowzati.archone.ordering.domain.model.OrderLine;
import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.ListRecentOrdersUsecase;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import com.flowzati.archone.ordering.domain.model.Order;
import com.flowzati.archone.ordering.application.command.PlaceOrderCommand;
import com.flowzati.archone.testsupport.OrderFixtures;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpMethod;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

  private static final String PLACE_ORDER_BODY = """
      {
        "ownerId": "00000000-0000-0000-0000-0000000000a1",
        "externalOrderNo": "EXT-1",
        "facilityId": "00000000-0000-0000-0000-0000000000b1",
        "shipToZone": "100",
        "shipToAddress": "台北市中正區重慶南路一段 122 號",
        "promisedDeliveryDate": "2026-08-01",
        "lines": [{"skuCode": "HOT-SKU", "quantity": 3}]
      }
      """;

  /** 沒有倉別的 body。倉別必填，這種請求不該建立任何訂單。 */
  private static final String BODY_WITHOUT_NODE = """
      {
        "ownerId": "00000000-0000-0000-0000-0000000000a1",
        "externalOrderNo": "EXT-1",
        "shipToZone": "100",
        "shipToAddress": "台北市中正區重慶南路一段 122 號",
        "promisedDeliveryDate": "2026-08-01",
        "lines": [{"skuCode": "HOT-SKU", "quantity": 3}]
      }
      """;

  private static final String SKU = "SKU-AVAILABLE";
  private static final Instant RECEIVED_AT = Instant.parse("2026-07-26T10:00:00Z");

  @Autowired
  private MockMvcTester mvc;

  @MockitoBean
  private PlaceOrderUsecase placeOrderUsecase;

  @MockitoBean
  private GetOrderUsecase getOrderUsecase;

  @MockitoBean
  private ListRecentOrdersUsecase listRecentOrdersUsecase;


  @Test
  @DisplayName("下單以 JSON body 送出，回傳與單筆查詢相同形狀的訂單表示")
  void shouldPlaceOrderFromJsonBodyAndReturnFullOrderRepresentation() {
    UUID orderId = UUID.randomUUID();
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenReturn(OrderFixtures.pendingOrder(orderId, SKU, 3, RECEIVED_AT));

    MvcTestResultAssert response = assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.orderId").isEqualTo(orderId.toString());
    response.bodyJson().extractingPath("$.ownerId").isEqualTo(OrderFixtures.OWNER_ID.toString());
    response.bodyJson().extractingPath("$.facilityId")
        .isEqualTo(OrderFixtures.FACILITY_ID.toString());
    response.bodyJson().extractingPath("$.shipToZone").isEqualTo("100");
    response.bodyJson().extractingPath("$.promisedDeliveryDate").isEqualTo("2026-08-01");
    response.bodyJson().extractingPath("$.status").isEqualTo("PENDING");
    response.bodyJson().extractingPath("$.receivedAt").isEqualTo(RECEIVED_AT.toString());
    // 上游沒送下單時刻時回 null，不重複收單時刻——否則呼叫端分不出「上游真的送了同一個
    // 時間」與「我們補了一個」。
    response.bodyJson().extractingPath("$.placedAt").isNull();

    // SKU 與數量移進行裡，訂單頂層不再有它們
    response.bodyJson().extractingPath("$.lines.length()").isEqualTo(1);
    response.bodyJson().extractingPath("$.lines[0].lineNo").isEqualTo(1);
    response.bodyJson().extractingPath("$.lines[0].skuCode").isEqualTo(SKU);
    response.bodyJson().extractingPath("$.lines[0].quantity").isEqualTo(3);
    response.bodyJson().doesNotHavePath("$.sku");
    response.bodyJson().doesNotHavePath("$.quantity");
  }

  @Test
  @DisplayName("下單被 Order aggregate 拒絕時回 400")
  void rejectsCommandsTheAggregateRefuses() {
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenThrow(new IllegalArgumentException(
            "Order must contain at least one line"));

    assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY)).hasStatus(400);
  }

  @Test
  @DisplayName("訂單行指向該貨主沒有的 SKU 時回 400——引用了不存在的東西")
  void rejectsOrdersReferencingUnknownCatalogData() {
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenThrow(new DataIntegrityViolationException("fk_order_lines_sku"));

    assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY)).hasStatus(400);
  }

  @Test
  @DisplayName("未指定倉別時回 400——倉別必填，領域層就會拒絕")
  void rejectsOrdersWithoutAWarehouse() {
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenThrow(new IllegalArgumentException("Fulfillment facility is required"));

    assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY_WITHOUT_NODE)).hasStatus(400);
    verify(placeOrderUsecase).placeOrder(any(PlaceOrderCommand.class));
  }

  @Test
  @DisplayName("指定該貨主沒掛的倉時回 400——複合外鍵擋下，不是應用層檢查")
  void rejectsWarehouseTheOwnerIsNotAssignedTo() {
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenThrow(new DataIntegrityViolationException("fk_orders_owner_facility"));

    assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY)).hasStatus(400);
  }

  @Test
  @DisplayName("同一貨主的上游單號重複時回 409——不是格式錯，是與既有狀態衝突")
  void rejectsDuplicateExternalOrderNoWithConflict() {
    when(placeOrderUsecase.placeOrder(any(PlaceOrderCommand.class)))
        .thenThrow(new DataIntegrityViolationException(
            "could not execute statement [ERROR: duplicate key value violates unique "
                + "constraint \"uq_orders_owner_external_no\"]"));

    assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY)).hasStatus(409);
  }

  @Test
  @DisplayName("多行訂單的每一行都要出現在回應裡，順序與行號不變")
  void serialisesEveryLineOfAMultiLineOrder() {
    // 收單入口目前只收一行，這張單只能以 rehydrate 造——但讀取與序列化的路徑必須撐得住
    // 多行，否則放寬時才第一次執行到，那時錯誤會以「畫面少一行」的形式出現。
    UUID orderId = UUID.randomUUID();
    UUID ownerId = OrderFixtures.OWNER_ID;
    Order twoLineOrder = Order.rehydrate(
        orderId,
        ownerId,
        "EXT-MULTI",
        OrderFixtures.deliveryTerms(),
        List.of(
            OrderLine.create(UUID.randomUUID(), 1, ownerId, "SKU-A", 3),
            OrderLine.create(UUID.randomUUID(), 2, ownerId, "SKU-B", 7)),
        OrderStatus.PENDING, RECEIVED_AT, null, null, null, null, null);
    when(listRecentOrdersUsecase.listRecent(20)).thenReturn(List.of(twoLineOrder));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/orders"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$[0].lines.length()").isEqualTo(2);
    response.bodyJson().extractingPath("$[0].lines[0].lineNo").isEqualTo(1);
    response.bodyJson().extractingPath("$[0].lines[0].skuCode").isEqualTo("SKU-A");
    response.bodyJson().extractingPath("$[0].lines[0].quantity").isEqualTo(3);
    response.bodyJson().extractingPath("$[0].lines[1].lineNo").isEqualTo(2);
    response.bodyJson().extractingPath("$[0].lines[1].skuCode").isEqualTo("SKU-B");
    response.bodyJson().extractingPath("$[0].lines[1].quantity").isEqualTo(7);
  }

  @Test
  @DisplayName("逐行的狀態由 header 導出——行上不再存它，但契約照樣揭露")
  void derivesEachLineStatusFromTheHeader() {
    // **這條性質原本由 OrderLine 自己的欄位保證**，而那是同一份資料存兩次：ship-complete
    // 之下一張單的所有行同進同出，值恆等於 header。欄位拿掉之後，性質搬到這裡。
    //
    // 刻意用非 PENDING 的狀態：PENDING 是新建的行本來就會有的值，拿它驗導出等於什麼都沒驗。
    UUID orderId = UUID.randomUUID();
    UUID ownerId = OrderFixtures.OWNER_ID;
    Order backordered = Order.rehydrate(
        orderId,
        ownerId,
        "EXT-DERIVED",
        OrderFixtures.deliveryTerms(),
        List.of(
            OrderLine.create(UUID.randomUUID(), 1, ownerId, "SKU-A", 3),
            OrderLine.create(UUID.randomUUID(), 2, ownerId, "SKU-B", 7)),
        OrderStatus.BACKORDERED, RECEIVED_AT, null, null, RECEIVED_AT.plusSeconds(1), null, null);
    when(listRecentOrdersUsecase.listRecent(20)).thenReturn(List.of(backordered));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/orders"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$[0].status").isEqualTo("BACKORDERED");
    response.bodyJson().extractingPath("$[0].lines[0].status").isEqualTo("BACKORDERED");
    response.bodyJson().extractingPath("$[0].lines[1].status").isEqualTo("BACKORDERED");
  }

  @Test
  @DisplayName("訂單只帶貨主識別碼，不帶名稱——名稱由呼叫端用它已載入的主檔自行解析")
  void carriesOwnerIdButNotOwnerName() {
    when(listRecentOrdersUsecase.listRecent(20)).thenReturn(List.of(
        OrderFixtures.pendingOrder(UUID.randomUUID(), SKU, 3, RECEIVED_AT)));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/orders"));

    response.bodyJson().extractingPath("$[0].ownerId")
        .isEqualTo(OrderFixtures.OWNER_ID.toString());
    // 把名稱塞進訂單契約會讓每次列表多一次主檔查詢，換到的是呼叫端本來就有的東西——
    // 它為了下單表單的下拉選單已經載過 /owners 了。
    response.bodyJson().doesNotHavePath("$[0].ownerName");
  }

  @Test
  @DisplayName("最近訂單列表預設取 20 筆")
  void shouldListRecentOrdersWithDefaultLimit() {
    UUID orderId = UUID.randomUUID();
    when(listRecentOrdersUsecase.listRecent(20))
        .thenReturn(List.of(OrderFixtures.pendingOrder(orderId, SKU, 3, RECEIVED_AT)));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/orders"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$[0].orderId").isEqualTo(orderId.toString());
    response.bodyJson().extractingPath("$[0].status").isEqualTo("PENDING");
    verify(placeOrderUsecase, never()).placeOrder(any(PlaceOrderCommand.class));
  }

  @ParameterizedTest(name = "limit={0} 應接受")
  @ValueSource(ints = {1, 100})
  @DisplayName("limit 在有效範圍內應被接受")
  void shouldAcceptLimitWithinRange(int limit) {
    when(listRecentOrdersUsecase.listRecent(limit)).thenReturn(List.of());

    assertThat(mvc.get().uri("/orders?limit=" + limit)).hasStatus(200);
  }

  @ParameterizedTest(name = "limit={0} 應回 400")
  @ValueSource(ints = {0, -1, 101})
  @DisplayName("limit 超出有效範圍應回 400，不靜默截斷")
  void shouldRejectLimitOutsideRange(int limit) {
    assertThat(mvc.get().uri("/orders?limit=" + limit)).hasStatus(400);

    verifyNoInteractions(listRecentOrdersUsecase);
  }

  @Test
  @DisplayName("非 POST 的方法不得建立訂單")
  void shouldNotPlaceOrderForNonPostMethods() {
    assertThat(mvc.method(HttpMethod.PUT).uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(PLACE_ORDER_BODY))
        .hasStatus(405);

    assertThat(mvc.method(HttpMethod.DELETE).uri("/orders")).hasStatus(405);

    verify(placeOrderUsecase, never()).placeOrder(any(PlaceOrderCommand.class));
  }
}
