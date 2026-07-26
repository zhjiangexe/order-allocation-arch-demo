package com.flowzati.archone.allocation.entrypoint.rest;

import com.flowzati.archone.allocation.application.usecase.GetStockPoolUsecase;
import com.flowzati.archone.allocation.domain.model.StockPool;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@WebMvcTest(StockPoolController.class)
class StockPoolControllerTest {

  @Autowired
  private MockMvcTester mvc;

  @MockitoBean
  private GetStockPoolUsecase getStockPoolUsecase;

  @Test
  @DisplayName("部分被預留的 SKU 應回報 on-hand、reserved 與 available-to-promise")
  void shouldReportAllThreeQuantities() {
    when(getStockPoolUsecase.getBySku("SKU-PARTIALLY-RESERVED"))
        .thenReturn(new StockPool(UUID.randomUUID(), "SKU-PARTIALLY-RESERVED", 10, 4, null));

    MvcTestResultAssert response = assertThat(mvc.get().uri("/stock-pool/SKU-PARTIALLY-RESERVED"));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.sku").isEqualTo("SKU-PARTIALLY-RESERVED");
    response.bodyJson().extractingPath("$.onHandQuantity").isEqualTo(10);
    response.bodyJson().extractingPath("$.reservedQuantity").isEqualTo(4);
    response.bodyJson().extractingPath("$.availableToPromise").isEqualTo(6);
  }

  @Test
  @DisplayName("沒有 StockPool 的 SKU 應回 404")
  void shouldReturnNotFoundForUnknownSku() {
    when(getStockPoolUsecase.getBySku("SKU-NOT-A-THING"))
        .thenThrow(new NoSuchElementException("StockPool not found: SKU-NOT-A-THING"));

    assertThat(mvc.get().uri("/stock-pool/SKU-NOT-A-THING")).hasStatus(404);
  }
}
