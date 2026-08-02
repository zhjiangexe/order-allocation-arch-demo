package com.flowzati.archone.stock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.common.IdGenerator;
import java.time.LocalDate;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StockPool ATP 領域模型")
class StockPoolTest {

  /**
   * 一條指向這一列庫存的明細。
   *
   * <p>非正數的數量不必在這裡驗——{@link StockMoveLine} 自己的建構子就擋掉了，那是它的不變式
   * 而不是庫存的。原本的 `replenish(int)` 要自己檢查，是因為它收的是一個裸數字。
   */
  private static StockMoveLine lineFor(StockPool stockPool, int quantity) {
    return new StockMoveLine(
        IdGenerator.nextId(), IdGenerator.nextId(), stockPool.getId(), quantity);
  }

  @Test
  @DisplayName("ATP 應由實際在庫量扣除已預留量計算")
  void derivesAvailableToPromiseFromOnHandAndReservedQuantities() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 4);

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(4);
    assertThat(stockPool.availableToPromise()).isEqualTo(6);
  }

  @Test
  @DisplayName("預留成功時只增加已預留量，不扣除實際在庫量")
  void reservesQuantityWithoutReducingOnHand() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 2);

    assertThat(stockPool.canReserve(5)).isTrue();
    stockPool.reserve(5);

    // Promising 階段只做軟預留，實際出庫不屬於這個 bounded context。
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("預留量剛好等於 ATP 時應成功並將 ATP 歸零")
  void reservesTheExactAvailableToPromiseQuantity() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 4);

    assertThat(stockPool.canReserve(6)).isTrue();
    stockPool.reserve(6);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(10);
    assertThat(stockPool.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("ATP 不足時應回傳失敗且所有數量保持不變")
  void leavesQuantitiesUnchangedWhenAvailableToPromiseIsInsufficient() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    boolean canReserve = stockPool.canReserve(4);

    // 不允許部分預留；數量不足時必須維持呼叫前的完整狀態。
    assertThat(canReserve).isFalse();
    assertThatThrownBy(() -> stockPool.reserve(4))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Insufficient ATP");
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("釋放 reservation 時應減少已預留量並恢復 ATP")
  void releasesReservedQuantity() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    stockPool.release(4);

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
    assertThat(stockPool.availableToPromise()).isEqualTo(7);
  }

  @Test
  @DisplayName("收貨時只增加實際在庫量，不改變已預留量")
  void receivesOnHandWithoutChangingReservedQuantity() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 7);

    stockPool.receive(lineFor(stockPool, 5));

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(15);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(8);
  }

  @Test
  @DisplayName("明細指向別的庫存列時應拒絕——貨會記到別人的批上，而數量對得起來")
  void rejectsALineThatAppliesToAnotherStockPool() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 0);
    StockPool other = StockFixtures.unexpiredBatch("SKU-1", 0, 0);

    // 配錯的後果不是數量錯，是**效期與入庫日全錯**——而數量的總和仍然對得上，所以沒有任何
    // 約束擋得下它。FEFO 會照那個錯的效期出貨。
    assertThatThrownBy(() -> stockPool.receive(lineFor(other, 5)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("applies to stock pool");

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
  }

  @Test
  @DisplayName("在庫量的增加沒有不帶明細的入口")
  void hasNoWayToIncreaseOnHandWithoutALine() {
    // 這一條守的是型別，不是行為：`StockPool` 上不存在任何以數量增加在庫量的公開方法。
    // 曾經那是 `replenish(int)`，而它讓任何拿得到 repository 的程式都能改庫存。
    assertThat(java.util.Arrays.stream(StockPool.class.getMethods())
        .filter(method -> method.getParameterCount() == 1)
        .filter(method -> method.getParameterTypes()[0] == int.class)
        .map(java.lang.reflect.Method::getName))
        .containsExactlyInAnyOrder("reserve", "release", "consume", "canReserve");
  }

  @ParameterizedTest(name = "[{index}] onHand={0}, reserved={1}")
  @MethodSource("invalidInitialQuantities")
  @DisplayName("建立 StockPool 時應拒絕不合法的初始數量")
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
  @DisplayName("建立 StockPool 時應拒絕空白 SKU")
  void rejectsBlankSku(String sku) {
    assertThatThrownBy(() -> StockFixtures.unexpiredBatch(sku, 10, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("SKU code is required");
  }

  @Test
  @DisplayName("效期當天尚未過期，過了一天才算")
  void isNotExpiredUntilTheDayAfterTheExpiryDate() {
    LocalDate expiry = LocalDate.of(2026, 6, 30);
    StockPool batch = StockFixtures.batchExpiringOn("SKU-1", expiry, 10, 0);

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
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 6);

    stockPool.consume(4);

    // 與 reserve 的差別是本質的：預留只鎖住額度、貨還在倉裡；消耗則是貨離開了。
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(6);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(2);
    assertThat(stockPool.availableToPromise()).isEqualTo(4);
  }

  @Test
  @DisplayName("消耗量超過已預留量時應拒絕且保持原狀態")
  void rejectsConsumeThatExceedsReservedQuantity() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 3);

    assertThatThrownBy(() -> stockPool.consume(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to consume cannot exceed reserved quantity");

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("預留時應拒絕非正數 quantity")
  void rejectsNonPositiveReserveQuantity(int quantity) {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 5);

    assertThatThrownBy(() -> stockPool.canReserve(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to reserve must be positive");
    assertThatThrownBy(() -> stockPool.reserve(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to reserve must be positive");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("釋放時應拒絕非正數 quantity")
  void rejectsNonPositiveReleaseQuantity(int quantity) {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 5);

    assertThatThrownBy(() -> stockPool.release(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release must be positive");
  }

  @Test
  @DisplayName("釋放量超過已預留量時應拒絕且保持原狀態")
  void rejectsReleaseThatExceedsReservedQuantity() {
    StockPool stockPool = StockFixtures.unexpiredBatch("SKU-1", 10, 3);

    assertThatThrownBy(() -> stockPool.release(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    // 失敗的 release 不得偷偷將 reserved quantity 歸零。
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
    assertThat(stockPool.availableToPromise()).isEqualTo(7);
  }
}
