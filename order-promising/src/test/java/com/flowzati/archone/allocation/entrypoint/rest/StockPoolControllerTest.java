package com.flowzati.archone.allocation.entrypoint.rest;

import com.flowzati.archone.allocation.application.usecase.GetStockPoolUsecase;
import com.flowzati.archone.allocation.domain.model.StockFixtures;
import com.flowzati.archone.common.time.BusinessCalendar;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@WebMvcTest(StockPoolController.class)
@Import(StockPoolControllerTest.FixedClockConfiguration.class)
class StockPoolControllerTest {

  /**
   * 營運日固定在 2026-06-01。可售與否是「效期 vs 今天」的比較，用系統時鐘的話這支測試會在
   * 某一天突然變色，而且不是因為程式改了。
   */
  private static final LocalDate TODAY = StockFixtures.TODAY;
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

  /**
   * 時刻取營運時區當天的零點。刻意不用 UTC 零點——那個瞬間在台北已經是早上八點，兩者剛好
   * 同一天，測試會通過但沒有證明日期是照營運時區算的。
   */
  @TestConfiguration
  static class FixedClockConfiguration {
    @Bean
    BusinessCalendar businessCalendar() {
      return new BusinessCalendar(
          Clock.fixed(TODAY.atStartOfDay(BUSINESS_ZONE).toInstant(), ZoneOffset.UTC),
          BUSINESS_ZONE.getId());
    }
  }

  @Autowired
  private MockMvcTester mvc;

  @MockitoBean
  private GetStockPoolUsecase getStockPoolUsecase;

  @Test
  @DisplayName("一個 SKU 分成三批時應逐批回報，並保持配貨會取用的順序")
  void shouldReportEveryBatchInAllocationOrder() {
    when(getStockPoolUsecase.getBatches(StockFixtures.OWNER_ID, "SKU-1")).thenReturn(List.of(
        StockFixtures.batchExpiringOn("SKU-1", LocalDate.of(2026, 8, 31), 60, 10),
        // 這一對同效期、不同入庫日——回應必須保持「早入庫的在前」。
        StockFixtures.batchArrivedOnExpiringOn(
            "SKU-1", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31), 40, 0),
        StockFixtures.batchArrivedOnExpiringOn(
            "SKU-1", LocalDate.of(2026, 2, 5), LocalDate.of(2026, 12, 31), 30, 0)));

    MvcTestResultAssert response = assertThat(
        mvc.get().uri("/stock-pool/SKU-1?ownerId=" + StockFixtures.OWNER_ID));

    response.hasStatus(200);
    response.bodyJson().extractingPath("$.sku").isEqualTo("SKU-1");
    response.bodyJson().extractingPath("$.batches.length()").isEqualTo(3);
    response.bodyJson().extractingPath("$.batches[0].expiryDate").isEqualTo("2026-08-31");
    response.bodyJson().extractingPath("$.batches[0].onHandQuantity").isEqualTo(60);
    response.bodyJson().extractingPath("$.batches[0].reservedQuantity").isEqualTo(10);
    response.bodyJson().extractingPath("$.batches[0].availableToPromise").isEqualTo(50);
    // 同效期的兩批以入庫日分先後，回應保持那個順序。
    response.bodyJson().extractingPath("$.batches[1].inDate").isEqualTo("2026-01-05");
    response.bodyJson().extractingPath("$.batches[2].inDate").isEqualTo("2026-02-05");
  }

  @Test
  @DisplayName("已過期的批應出現在回應中並標記，而不是被略過")
  void shouldMarkExpiredBatchesRatherThanOmitThem() {
    when(getStockPoolUsecase.getBatches(StockFixtures.OWNER_ID, "SKU-1")).thenReturn(List.of(
        StockFixtures.expiredBatch("SKU-1", 25),
        StockFixtures.unexpiredBatch("SKU-1", 10, 0)));

    MvcTestResultAssert response = assertThat(
        mvc.get().uri("/stock-pool/SKU-1?ownerId=" + StockFixtures.OWNER_ID));

    // 略過的話，「有 25 件但一件都出不了」與「什麼都沒有」在畫面上長得一模一樣，
    // 而前者要報廢、後者要進貨。
    response.hasStatus(200);
    response.bodyJson().extractingPath("$.batches[0].expired").isEqualTo(true);
    response.bodyJson().extractingPath("$.batches[0].onHandQuantity").isEqualTo(25);
    response.bodyJson().extractingPath("$.batches[1].expired").isEqualTo(false);
  }

  @Test
  @DisplayName("沒過期但被預留光的批應回報 expired=false 且 ATP=0——與過期是不同的事")
  void shouldDistinguishAFullyReservedBatchFromAnExpiredOne() {
    when(getStockPoolUsecase.getBatches(StockFixtures.OWNER_ID, "SKU-1")).thenReturn(List.of(
        StockFixtures.fullyReservedBatch("SKU-1", 40)));

    // 兩個欄位各講一件事，讀的人合起來就知道是「過期了」還是「被預留光了」，
    // 不需要第三個欄位轉述。
    MvcTestResultAssert response = assertThat(
        mvc.get().uri("/stock-pool/SKU-1?ownerId=" + StockFixtures.OWNER_ID));
    response.bodyJson().extractingPath("$.batches[0].expired").isEqualTo(false);
    response.bodyJson().extractingPath("$.batches[0].availableToPromise").isEqualTo(0);
  }

  @Test
  @DisplayName("效期當天仍應標記為未過期")
  void shouldTreatTheExpiryDateItselfAsNotExpired() {
    when(getStockPoolUsecase.getBatches(StockFixtures.OWNER_ID, "SKU-1")).thenReturn(List.of(
        StockFixtures.batchExpiringOn("SKU-1", TODAY, 10, 0)));

    assertThat(mvc.get().uri("/stock-pool/SKU-1?ownerId=" + StockFixtures.OWNER_ID))
        .bodyJson().extractingPath("$.batches[0].expired").isEqualTo(false);
  }

  @Test
  @DisplayName("該貨主一批都沒有的 SKU 應回 404")
  void shouldReturnNotFoundForUnknownSku() {
    when(getStockPoolUsecase.getBatches(StockFixtures.OWNER_ID, "SKU-NOT-A-THING"))
        .thenThrow(new NoSuchElementException("No stock held for SKU SKU-NOT-A-THING"));

    assertThat(mvc.get().uri("/stock-pool/SKU-NOT-A-THING?ownerId=" + StockFixtures.OWNER_ID))
        .hasStatus(404);
  }

  @Test
  @DisplayName("沒帶 ownerId 時應回 400——SKU 代碼跨貨主撞號，只憑它問不出答案")
  void shouldRejectAQueryWithoutAnOwner() {
    assertThat(mvc.get().uri("/stock-pool/SKU-1")).hasStatus(400);
  }
}
