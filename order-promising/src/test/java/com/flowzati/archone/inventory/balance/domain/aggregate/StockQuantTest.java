package com.flowzati.archone.inventory.balance.domain.aggregate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StockQuant ATP 領域模型")
class StockQuantTest {

  @Test
  @DisplayName("ATP 應由實際在庫量扣除已預留量計算")
  void derivesAvailableToPromiseFromOnHandAndReservedQuantities() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 4);

    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(4);
    assertThat(stockQuant.availableToPromise()).isEqualTo(6);
  }

  @Test
  @DisplayName("預留成功時只增加已預留量，不扣除實際在庫量")
  void reservesQuantityWithoutReducingOnHand() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 2);

    assertThat(stockQuant.canReserve(5)).isTrue();
    stockQuant.reserve(5);

    // Promising 階段只做軟預留，實際出庫不屬於這個 bounded context。
    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(7);
    assertThat(stockQuant.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("預留量剛好等於 ATP 時應成功並將 ATP 歸零")
  void reservesTheExactAvailableToPromiseQuantity() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 4);

    assertThat(stockQuant.canReserve(6)).isTrue();
    stockQuant.reserve(6);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(10);
    assertThat(stockQuant.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("ATP 不足時應回傳失敗且所有數量保持不變")
  void leavesQuantitiesUnchangedWhenAvailableToPromiseIsInsufficient() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    boolean canReserve = stockQuant.canReserve(4);

    // 不允許部分預留；數量不足時必須維持呼叫前的完整狀態。
    assertThat(canReserve).isFalse();
    assertThatThrownBy(() -> stockQuant.reserve(4))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Insufficient ATP");
    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(7);
    assertThat(stockQuant.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("釋放 reservation 時應減少已預留量並恢復 ATP")
  void releasesReservedQuantity() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    stockQuant.release(4);

    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(3);
    assertThat(stockQuant.availableToPromise()).isEqualTo(7);
  }

  @Test
  @DisplayName("完成的搬運明細增加實際在庫量，不改變已預留量")
  void receivesMoveLineWithoutChangingReservedQuantity() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    stockQuant.receive(new StockMoveLine(
        IdGenerator.nextId(), IdGenerator.nextId(), stockQuant.getId(), 5));

    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(15);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(7);
    assertThat(stockQuant.availableToPromise()).isEqualTo(8);
  }

  @Test
  @DisplayName("收貨明細不可屬於另一列 StockQuant")
  void rejectsMoveLineForAnotherStockQuant() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 0);

    assertThatThrownBy(() -> stockQuant.receive(new StockMoveLine(
        IdGenerator.nextId(), IdGenerator.nextId(), IdGenerator.nextId(), 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Move line belongs to another stock quant");

    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
  }

  @ParameterizedTest(name = "[{index}] onHand={0}, reserved={1}")
  @MethodSource("invalidInitialQuantities")
  @DisplayName("建立 StockQuant 時應拒絕不合法的初始數量")
  void rejectsInvalidInitialQuantities(
      int onHandQuantity,
      int reservedQuantity,
      String expectedMessage
  ) {
    assertThatThrownBy(() -> StockFixtures.unexpiredBatch("SKU-1", onHandQuantity, reservedQuantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessage);
  }

  static Stream<Arguments> invalidInitialQuantities() {
    return Stream.of(
        Arguments.of(-1, 0, "On-hand quantity cannot be negative"),
        Arguments.of(1, -1, "Reserved quantity cannot be negative"),
        Arguments.of(1, 2, "Reserved quantity cannot exceed on-hand quantity")
    );
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {" ", "\t"})
  @DisplayName("建立 StockQuant 時應拒絕空白 SKU")
  void rejectsBlankSku(String sku) {
    assertThatThrownBy(() -> StockFixtures.unexpiredBatch(sku, 10, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SKU code is required");
  }

  @Test
  @DisplayName("效期當天尚未過期，過了一天才算")
  void isNotExpiredUntilTheDayAfterTheExpiryDate() {
    LocalDate expiry = LocalDate.of(2026, 6, 30);
    StockQuant batch = StockFixtures.batchExpiringOn("SKU-1", expiry, 10, 0);

    assertThat(batch.isExpired(expiry.minusDays(1))).isFalse();
    // 效期當天仍可出貨——「到期」指的是那一天結束，不是那一天開始。
    assertThat(batch.isExpired(expiry)).isFalse();
    assertThat(batch.isExpired(expiry.plusDays(1))).isTrue();
  }

  @Test
  @DisplayName("過期與否只看效期，不看數量")
  void expiryIgnoresQuantity() {
    LocalDate expiry = LocalDate.of(2026, 6, 30);

    // 「沒貨」與「過期」是兩件事：全部預留完的批仍然沒過期，過期但滿手的批仍然過期。
    // 兩者合起來才是「配不配得到」，而那個判斷屬於查詢，不屬於這個方法。
    assertThat(StockFixtures.batchExpiringOn("SKU-1", expiry, 10, 10)
        .isExpired(expiry.minusDays(1))).isFalse();
    assertThat(StockFixtures.batchExpiringOn("SKU-1", expiry, 10, 0)
        .isExpired(expiry.plusDays(1))).isTrue();
  }

  @Test
  @DisplayName("消耗應同時扣除已預留量與實際在庫量")
  void consumeReducesBothReservedAndOnHand() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 6);

    stockQuant.consume(4);

    // 與 reserve 的差別是本質的：預留只鎖住額度、貨還在倉裡；消耗則是貨離開了。
    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(6);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(2);
    assertThat(stockQuant.availableToPromise()).isEqualTo(4);
  }

  @Test
  @DisplayName("消耗量超過已預留量時應拒絕且保持原狀態")
  void rejectsConsumeThatExceedsReservedQuantity() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 3);

    assertThatThrownBy(() -> stockQuant.consume(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to consume cannot exceed reserved quantity");

    assertThat(stockQuant.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(3);
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("預留時應拒絕非正數 quantity")
  void rejectsNonPositiveReserveQuantity(int quantity) {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 5);

    assertThatThrownBy(() -> stockQuant.canReserve(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to reserve must be positive");
    assertThatThrownBy(() -> stockQuant.reserve(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to reserve must be positive");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("釋放時應拒絕非正數 quantity")
  void rejectsNonPositiveReleaseQuantity(int quantity) {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 5);

    assertThatThrownBy(() -> stockQuant.release(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release must be positive");
  }

  @Test
  @DisplayName("釋放量超過已預留量時應拒絕且保持原狀態")
  void rejectsReleaseThatExceedsReservedQuantity() {
    StockQuant stockQuant = StockFixtures.unexpiredBatch("SKU-1", 10, 3);

    assertThatThrownBy(() -> stockQuant.release(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    // 失敗的 release 不得偷偷將 reserved quantity 歸零。
    assertThat(stockQuant.getReservedQuantity()).isEqualTo(3);
    assertThat(stockQuant.availableToPromise()).isEqualTo(7);
  }
}
