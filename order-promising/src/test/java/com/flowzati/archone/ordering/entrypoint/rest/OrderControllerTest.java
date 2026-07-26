package com.flowzati.archone.ordering.entrypoint.rest;

import com.flowzati.archone.ordering.application.usecase.GetOrderUsecase;
import com.flowzati.archone.ordering.application.usecase.ListRecentOrdersUsecase;
import com.flowzati.archone.ordering.application.usecase.PlaceOrderUsecase;
import com.flowzati.archone.ordering.domain.model.Order;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@WebMvcTest(OrderController.class)
class OrderControllerTest {

  private static final String SKU = "SKU-AVAILABLE";
  private static final Instant PLACED_AT = Instant.parse("2026-07-26T10:00:00Z");

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
    when(placeOrderUsecase.placeOrder(SKU, 3))
        .thenReturn(Order.place(orderId, SKU, 3, PLACED_AT));

    MvcTestResultAssert response = assertThat(mvc.post().uri("/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"sku\":\"" + SKU + "\",\"quantity\":3}"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.orderId").isEqualTo(orderId.toString());
    response.bodyJson().extractingPath("$.sku").isEqualTo(SKU);
    response.bodyJson().extractingPath("$.quantity").isEqualTo(3);
    response.bodyJson().extractingPath("$.status").isEqualTo("PENDING");
    response.bodyJson().extractingPath("$.placedAt").isEqualTo(PLACED_AT.toString());
  }

  @Test
  @DisplayName("最近訂單列表預設取 20 筆")
  void shouldListRecentOrdersWithDefaultLimit() {
    UUID orderId = UUID.randomUUID();
    when(listRecentOrdersUsecase.listRecent(20))
        .thenReturn(List.of(Order.place(orderId, SKU, 3, PLACED_AT)));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/orders"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$[0].orderId").isEqualTo(orderId.toString());
    response.bodyJson().extractingPath("$[0].status").isEqualTo("PENDING");
    verify(placeOrderUsecase, never()).placeOrder(SKU, 3);
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
        .content("{\"sku\":\"" + SKU + "\",\"quantity\":3}"))
        .hasStatus(405);

    assertThat(mvc.method(HttpMethod.DELETE).uri("/orders")).hasStatus(405);

    verify(placeOrderUsecase, never()).placeOrder(SKU, 3);
  }
}
