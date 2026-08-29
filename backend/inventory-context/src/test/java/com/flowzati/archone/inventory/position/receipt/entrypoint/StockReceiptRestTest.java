package com.flowzati.archone.inventory.position.receipt.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flowzati.archone.inventory.position.application.StockReceiptApplicationFacade;
import com.flowzati.archone.inventory.position.application.StockReceiptRequest;
import com.flowzati.archone.inventory.position.application.StockReceiptRequestConflictException;
import com.flowzati.archone.inventory.position.entrypoint.rest.StockReceiptRest;
import com.flowzati.archone.support.spring.web.validation.GlobalRestExceptionHandler;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(StockReceiptRest.class)
@Import(GlobalRestExceptionHandler.class)
@TestPropertySource(
        properties = "archone.web.validation.message-basenames="
                + "classpath:i18n/validation/constraints_template,"
                + "classpath:i18n/validation/problem_detail,"
                + "classpath:i18n/inventory/request_field")
class StockReceiptRestTest {

    private static final UUID RECEIPT_ID = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
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

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StockReceiptApplicationFacade facade;

    @Test
    @DisplayName("同步收貨只映射 request，並直接呼叫 transactional use case")
    void shouldConfirmReceiptSynchronously() {
        var response = assertThat(mvc.post()
                .uri("/stock-receipts")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY));

        response.hasStatusOk();
        response.bodyJson().extractingPath("$.receiptId").isEqualTo(RECEIPT_ID.toString());
        response.bodyJson().extractingPath("$.sku").isEqualTo("HOT-SKU");
        response.bodyJson().extractingPath("$.quantity").isEqualTo(500);
        response.bodyJson().doesNotHavePath("$.allocatedOrderIds");
        response.bodyJson().doesNotHavePath("$.limitReached");

        ArgumentCaptor<StockReceiptRequest> captor = ArgumentCaptor.forClass(StockReceiptRequest.class);
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
                .when(facade)
                .confirm(any());

        assertThat(mvc.post()
                        .uri("/stock-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .hasStatus(400);

        verify(facade).confirm(any());
    }

    @Test
    @DisplayName("同一 receiptId 改送不同內容時回 409")
    void shouldRejectAConflictingIdempotencyKey() {
        doThrow(new StockReceiptRequestConflictException(RECEIPT_ID))
                .when(facade)
                .confirm(any());

        assertThat(mvc.post()
                        .uri("/stock-receipts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .hasStatus(409);

        verify(facade).confirm(any());
    }

    @Test
    @DisplayName("缺少 receiptId 時回 400，避免 retry 重複入庫")
    void shouldRequireAnIdempotencyKey() {
        var response = assertThat(mvc.post()
                .uri("/stock-receipts")
                .locale(Locale.ENGLISH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"receiptId\": \"00000000-0000-0000-0000-0000000000f1\",", "")));

        response.hasStatus(400);
        response.bodyJson().extractingPath("$.type").isEqualTo("urn:archone:problem:request-validation");
        response.bodyJson().extractingPath("$.title").isEqualTo("Request validation failed");
        response.bodyJson().extractingPath("$.status").isEqualTo(400);
        response.bodyJson().extractingPath("$.detail").isEqualTo("One or more request fields are invalid");
        response.bodyJson().extractingPath("$.instance").isEqualTo("/stock-receipts");
        response.bodyJson().extractingPath("$.errors.length()").isEqualTo(1);
        response.bodyJson().extractingPath("$.errors[0].field").isEqualTo("receiptId");
        response.bodyJson().extractingPath("$.errors[0].code").isEqualTo("NotNull");
        response.bodyJson().extractingPath("$.errors[0].message").isEqualTo("Receipt ID is required");
        response.bodyJson().doesNotHavePath("$.errors[0].rejectedValue");

        verify(facade, never()).confirm(any());
    }

    @Test
    @DisplayName("request 驗證訊息依請求語系回傳繁體中文")
    void shouldLocalizeRequestValidationMessage() {
        var response = assertThat(mvc.post()
                .uri("/stock-receipts")
                .locale(Locale.TAIWAN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"receiptId\": \"00000000-0000-0000-0000-0000000000f1\",", "")));

        response.hasStatus(400);
        response.bodyJson().extractingPath("$.title").isEqualTo("請求驗證失敗");
        response.bodyJson().extractingPath("$.detail").isEqualTo("一個或多個請求欄位不合法");
        response.bodyJson().extractingPath("$.errors[0].message").isEqualTo("收貨識別碼為必填");

        verify(facade, never()).confirm(any());
    }

    @Test
    @DisplayName("共用 NotNull 模板會套用對應的 request 欄位名稱")
    void shouldCombineConstraintMessageWithRequestFieldName() {
        var response = assertThat(mvc.post()
                .uri("/stock-receipts")
                .locale(Locale.ENGLISH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"quantity\": 500", "\"quantity\": null")));

        response.hasStatus(400);
        response.bodyJson().extractingPath("$.errors[0].field").isEqualTo("quantity");
        response.bodyJson().extractingPath("$.errors[0].code").isEqualTo("NotNull");
        response.bodyJson().extractingPath("$.errors[0].message").isEqualTo("Received quantity is required");

        verify(facade, never()).confirm(any());
    }

    @Test
    @DisplayName("同一個 request 的所有欄位錯誤都會包進 ProblemDetail")
    void shouldReturnEveryRequestValidationError() {
        var response = assertThat(mvc.post()
                .uri("/stock-receipts")
                .locale(Locale.ENGLISH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY.replace("\"receiptId\": \"00000000-0000-0000-0000-0000000000f1\",", "")
                        .replace("\"quantity\": 500", "\"quantity\": null")));

        response.hasStatus(400);
        response.bodyJson().extractingPath("$.errors.length()").isEqualTo(2);

        verify(facade, never()).confirm(any());
    }
}
