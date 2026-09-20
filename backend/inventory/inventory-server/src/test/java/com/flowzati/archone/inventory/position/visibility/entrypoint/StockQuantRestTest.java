package com.flowzati.archone.inventory.position.visibility.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.foundation.time.BusinessClock;
import com.flowzati.archone.inventory.balance.application.result.StockQuantView;
import com.flowzati.archone.inventory.balance.application.usecase.GetStockQuantUsecase;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.entrypoint.rest.StockQuantRest;
import com.flowzati.archone.inventory.position.onhand.testsupport.StockFixtures;
import com.flowzati.archone.inventory.testsupport.InventoryFixtures;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(StockQuantRest.class)
@Import(StockQuantRestTest.FixedClockConfiguration.class)
class StockQuantRestTest {

    /**
     * 營運日固定在 2026-06-01。可售與否是「效期 vs 今天」的比較，用系統時鐘的話這支測試會在
     * 某一天突然變色，而且不是因為程式改了。
     */
    private static final LocalDate TODAY = StockFixtures.TODAY;

    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Taipei");

    private static final String QUERY =
            "/stock-pool?ownerId=" + StockFixtures.OWNER_ID + "&locationId=" + StockFixtures.LOCATION_ID;

    /**
     * 時刻取營運時區當天的零點。刻意不用 UTC 零點——那個瞬間在台北已經是早上八點，兩者剛好
     * 同一天，測試會通過但沒有證明日期是照營運時區算的。
     */
    @TestConfiguration
    static class FixedClockConfiguration {
        @Bean
        BusinessClock businessClock() {
            return InventoryFixtures.businessClock(
                    Clock.fixed(TODAY.atStartOfDay(BUSINESS_ZONE).toInstant(), ZoneOffset.UTC), BUSINESS_ZONE.getId());
        }
    }

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private GetStockQuantUsecase getStockQuantUsecase;

    @Test
    @DisplayName("一個 SKU 分成三批時應逐批回報，並保持配貨會取用的順序")
    void shouldReportEveryBatchInAllocationOrder() {
        givenWarehouseHolds(Map.of(
                "SKU-1",
                List.of(
                        StockFixtures.batchExpiringOn("SKU-1", LocalDate.of(2026, 8, 31), 60, 10),
                        // 這一對同效期、不同入庫日——回應必須保持「早入庫的在前」。
                        StockFixtures.batchArrivedOnExpiringOn(
                                "SKU-1", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 12, 31), 40, 0),
                        StockFixtures.batchArrivedOnExpiringOn(
                                "SKU-1", LocalDate.of(2026, 2, 5), LocalDate.of(2026, 12, 31), 30, 0))));

        MvcTestResultAssert response = assertThat(mvc.get().uri(QUERY));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$.skus.length()").isEqualTo(1);
        response.bodyJson().extractingPath("$.skus[0].sku").isEqualTo("SKU-1");
        response.bodyJson().extractingPath("$.skus[0].batches.length()").isEqualTo(3);
        response.bodyJson().extractingPath("$.skus[0].batches[0].expiryDate").isEqualTo("2026-08-31");
        response.bodyJson()
                .extractingPath("$.skus[0].batches[0].onHandQuantity")
                .isEqualTo(60);
        response.bodyJson()
                .extractingPath("$.skus[0].batches[0].reservedQuantity")
                .isEqualTo(10);
        response.bodyJson()
                .extractingPath("$.skus[0].batches[0].availableToPromise")
                .isEqualTo(50);
        // Java domain 已改稱 quant，但 v1 HTTP payload 暫時保留既有欄位，避免純內部改名破壞呼叫端。
        response.bodyJson().extractingPath("$.skus[0].batches[0].stockPoolId").isNotNull();
        response.bodyJson().doesNotHavePath("$.skus[0].batches[0].stockQuantId");
        // 同效期的兩批以入庫日分先後，回應保持那個順序。
        response.bodyJson().extractingPath("$.skus[0].batches[1].inDate").isEqualTo("2026-01-05");
        response.bodyJson().extractingPath("$.skus[0].batches[2].inDate").isEqualTo("2026-02-05");
    }

    @Test
    @DisplayName("這個倉的每一個 SKU 都應回報，且分組順序照 usecase 給的")
    void shouldReportEverySkuHeldInTheWarehouse() {
        // LinkedHashMap：分組的順序是查詢排出來的，回應不得重排。用會重排鍵的 map 收就丟掉了。
        Map<String, List<StockQuant>> held = new LinkedHashMap<>();
        held.put("SKU-1", List.of(StockFixtures.unexpiredBatch("SKU-1", 10, 0)));
        held.put("SKU-2", List.of(StockFixtures.unexpiredBatch("SKU-2", 20, 5)));
        givenWarehouseHolds(held);

        MvcTestResultAssert response = assertThat(mvc.get().uri(QUERY));

        response.bodyJson().extractingPath("$.skus.length()").isEqualTo(2);
        response.bodyJson().extractingPath("$.skus[0].sku").isEqualTo("SKU-1");
        response.bodyJson().extractingPath("$.skus[1].sku").isEqualTo("SKU-2");
    }

    @Test
    @DisplayName("已過期的批應出現在回應中並標記，而不是被略過")
    void shouldMarkExpiredBatchesRatherThanOmitThem() {
        givenWarehouseHolds(Map.of(
                "SKU-1",
                List.of(StockFixtures.expiredBatch("SKU-1", 25), StockFixtures.unexpiredBatch("SKU-1", 10, 0))));

        MvcTestResultAssert response = assertThat(mvc.get().uri(QUERY));

        // 略過的話，「有 25 件但一件都出不了」與「什麼都沒有」在畫面上長得一模一樣，
        // 而前者要報廢、後者要進貨。
        response.hasStatus(200);
        response.bodyJson().extractingPath("$.skus[0].batches[0].expired").isEqualTo(true);
        response.bodyJson()
                .extractingPath("$.skus[0].batches[0].onHandQuantity")
                .isEqualTo(25);
        response.bodyJson().extractingPath("$.skus[0].batches[1].expired").isEqualTo(false);
    }

    @Test
    @DisplayName("沒過期但被預留光的批應回報 expired=false 且 ATP=0——與過期是不同的事")
    void shouldDistinguishAFullyReservedBatchFromAnExpiredOne() {
        givenWarehouseHolds(Map.of("SKU-1", List.of(StockFixtures.fullyReservedBatch("SKU-1", 40))));

        // 兩個欄位各講一件事，讀的人合起來就知道是「過期了」還是「被預留光了」，
        // 不需要第三個欄位轉述。
        MvcTestResultAssert response = assertThat(mvc.get().uri(QUERY));
        response.bodyJson().extractingPath("$.skus[0].batches[0].expired").isEqualTo(false);
        response.bodyJson()
                .extractingPath("$.skus[0].batches[0].availableToPromise")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("效期當天仍應標記為未過期")
    void shouldTreatTheExpiryDateItselfAsNotExpired() {
        givenWarehouseHolds(Map.of("SKU-1", List.of(StockFixtures.batchExpiringOn("SKU-1", TODAY, 10, 0))));

        assertThat(mvc.get().uri(QUERY))
                .bodyJson()
                .extractingPath("$.skus[0].batches[0].expired")
                .isEqualTo(false);
    }

    @Test
    @DisplayName("一批都沒有的倉應回 200 與空清單，不是 404")
    void shouldAnswerAnEmptyWarehouseWithAnEmptyList() {
        givenWarehouseHolds(Map.of());

        // 「這個倉什麼都沒放」是正常答案，不是問了不存在的東西——而且那正是新倉上線時的狀態，
        // 回 404 會讓最需要打開它的那個時刻反而看不到畫面。
        MvcTestResultAssert response = assertThat(mvc.get().uri(QUERY));
        response.hasStatus(200);
        response.bodyJson().extractingPath("$.skus.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("沒帶 ownerId 時應回 400——SKU 代碼跨貨主撞號，只憑倉問不出答案")
    void shouldRejectAQueryWithoutAnOwner() {
        assertThat(mvc.get().uri("/stock-pool?locationId=" + StockFixtures.LOCATION_ID))
                .hasStatus(400);
    }

    @Test
    @DisplayName("沒帶 locationId 時應回 400——多庫位設施必須指定實際庫存端點")
    void shouldRejectAQueryWithoutALocation() {
        assertThat(mvc.get().uri("/stock-pool?ownerId=" + StockFixtures.OWNER_ID))
                .hasStatus(400);
    }

    private void givenWarehouseHolds(Map<String, List<StockQuant>> batchesBySku) {
        when(getStockQuantUsecase.getBatchesInLocation(StockFixtures.OWNER_ID, StockFixtures.LOCATION_ID))
                .thenReturn(batchesBySku.values().stream()
                        .flatMap(List::stream)
                        .map(StockQuantRestTest::viewOf)
                        .toList());
    }

    private static StockQuantView viewOf(StockQuant batch) {
        return new StockQuantView(
                batch.getId(),
                batch.getSkuCode(),
                batch.getInDate(),
                batch.getExpiryDate(),
                batch.getOnHandQuantity(),
                batch.getReservedQuantity());
    }
}
