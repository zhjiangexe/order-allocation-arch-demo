package com.flowzati.archone.stock.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.stock.application.command.ConfirmStockReceiptCommand;
import com.flowzati.archone.stock.application.receipt.StockReceiptApplicationFacade;
import com.flowzati.archone.stock.application.receipt.StockReceiptRequest;
import com.flowzati.archone.stock.application.receipt.StockReceiptRequestConflictException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(StockReceiptController.class)
class StockReceiptControllerTest {

  private static final UUID RECEIPT_ID =
      UUID.fromString("00000000-0000-0000-0000-0000000000f1");
  private static final String BODY = """
      {
        "receiptId": "00000000-0000-0000-0000-0000000000f1",
        "ownerId": "00000000-0000-0000-0000-0000000000a1",
        "facilityId": "00000000-0000-0000-0000-0000000000b1",
        "locationId": "00000000-0000-0000-0000-0000000000c1",
        "sku": "HOT-SKU",
        "inDate": "2026-01-05",
        "expiryDate": "2026-12-31",
        "quantity": 500
      }
      """;

  @Autowired private MockMvcTester mvc;
  @MockitoBean private StockReceiptApplicationFacade facade;

  @Test
  @DisplayName("同步收貨只映射 request，並直接呼叫 transactional use case")
  void shouldConfirmReceiptSynchronously() {
    var response = assertThat(mvc.post().uri("/stock-receipts")
        .contentType(MediaType.APPLICATION_JSON).content(BODY));

    response.hasStatusOk();
    response.bodyJson().extractingPath("$.receiptId").isEqualTo(RECEIPT_ID.toString());
    response.bodyJson().extractingPath("$.sku").isEqualTo("HOT-SKU");
    response.bodyJson().extractingPath("$.quantity").isEqualTo(500);
    response.bodyJson().doesNotHavePath("$.allocatedOrderIds");
    response.bodyJson().doesNotHavePath("$.limitReached");

    ArgumentCaptor<StockReceiptRequest> captor =
        ArgumentCaptor.forClass(StockReceiptRequest.class);
    verify(facade).confirm(captor.capture());
    assertThat(captor.getValue().receiptId()).isEqualTo(RECEIPT_ID);
    assertThat(captor.getValue().command().facilityId().toString())
        .isEqualTo("00000000-0000-0000-0000-0000000000b1");
    assertThat(captor.getValue().command().locationId().toString())
        .isEqualTo("00000000-0000-0000-0000-0000000000c1");
  }

  @Test
  @DisplayName("use case 拒絕收貨時轉成 400")
  void shouldMapAnApplicationInputErrorToBadRequest() {
    doThrow(new IllegalArgumentException("Location is outside facility"))
        .when(facade).confirm(any());

    assertThat(mvc.post().uri("/stock-receipts")
        .contentType(MediaType.APPLICATION_JSON).content(BODY)).hasStatus(400);

    verify(facade).confirm(any());
  }

  @Test
  @DisplayName("同一 receiptId 改送不同內容時回 409")
  void shouldRejectAConflictingIdempotencyKey() {
    doThrow(new StockReceiptRequestConflictException(RECEIPT_ID))
        .when(facade).confirm(any());

    assertThat(mvc.post().uri("/stock-receipts")
        .contentType(MediaType.APPLICATION_JSON).content(BODY)).hasStatus(409);

    verify(facade).confirm(any());
  }

  @Test
  @DisplayName("缺少 receiptId 時回 400，避免 retry 重複入庫")
  void shouldRequireAnIdempotencyKey() {
    assertThat(mvc.post().uri("/stock-receipts")
        .contentType(MediaType.APPLICATION_JSON)
        .content(BODY.replace(
            "\"receiptId\": \"00000000-0000-0000-0000-0000000000f1\",", "")))
        .hasStatus(400);

    verify(facade, never()).confirm(any());
  }
}
