package com.flowzati.archone.allocation.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("StockPool ATP 領域模型")
class StockPoolTest {

  @Test
  @DisplayName("ATP 應由實際在庫量扣除已預留量計算")
  void derivesAvailableToPromiseFromOnHandAndReservedQuantities() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 4, 0L);

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(4);
    assertThat(stockPool.availableToPromise()).isEqualTo(6);
  }

  @Test
  @DisplayName("預留成功時只增加已預留量，不扣除實際在庫量")
  void reservesQuantityWithoutReducingOnHand() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 2, 0L);

    boolean reserved = stockPool.tryReserve(5);

    // Promising 階段只做軟預留，實際出庫不屬於這個 bounded context。
    assertThat(reserved).isTrue();
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("預留量剛好等於 ATP 時應成功並將 ATP 歸零")
  void reservesTheExactAvailableToPromiseQuantity() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 4, 0L);

    assertThat(stockPool.tryReserve(6)).isTrue();
    assertThat(stockPool.getReservedQuantity()).isEqualTo(10);
    assertThat(stockPool.availableToPromise()).isZero();
  }

  @Test
  @DisplayName("ATP 不足時應回傳失敗且所有數量保持不變")
  void leavesQuantitiesUnchangedWhenAvailableToPromiseIsInsufficient() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 7, 0L);

    boolean reserved = stockPool.tryReserve(4);

    // 不允許部分預留；數量不足時必須維持呼叫前的完整狀態。
    assertThat(reserved).isFalse();
    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(3);
  }

  @Test
  @DisplayName("釋放 reservation 時應減少已預留量並恢復 ATP")
  void releasesReservedQuantity() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 7, 0L);

    stockPool.release(4);

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(10);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
    assertThat(stockPool.availableToPromise()).isEqualTo(7);
  }

  @Test
  @DisplayName("補貨時只增加實際在庫量，不改變已預留量")
  void replenishesOnHandWithoutChangingReservedQuantity() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 7, 0L);

    stockPool.replenish(5);

    assertThat(stockPool.getOnHandQuantity()).isEqualTo(15);
    assertThat(stockPool.getReservedQuantity()).isEqualTo(7);
    assertThat(stockPool.availableToPromise()).isEqualTo(8);
  }

  @ParameterizedTest(name = "[{index}] onHand={0}, reserved={1}")
  @MethodSource("invalidInitialQuantities")
  @DisplayName("建立 StockPool 時應拒絕不合法的初始數量")
  void rejectsInvalidInitialQuantities(
      int onHandQuantity,
      int reservedQuantity,
      String expectedMessage
  ) {
    assertThatThrownBy(
        () -> new StockPool(1L, "SKU-1", onHandQuantity, reservedQuantity, 0L)
    ).isInstanceOf(IllegalArgumentException.class)
        .hasMessage(expectedMessage);
  }

  static Stream<Arguments> invalidInitialQuantities() {
    return Stream.of(
        Arguments.of(-1, 0, "On-hand quantity cannot be negative"),
        Arguments.of(1, -1, "Reserved quantity cannot be negative"),
        Arguments.of(1, 2, "Reserved quantity cannot exceed on-hand quantity")
    );
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("預留時應拒絕非正數 quantity")
  void rejectsNonPositiveReserveQuantity(int quantity) {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 5, 0L);

    assertThatThrownBy(() -> stockPool.tryReserve(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to reserve must be positive");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("釋放時應拒絕非正數 quantity")
  void rejectsNonPositiveReleaseQuantity(int quantity) {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 5, 0L);

    assertThatThrownBy(() -> stockPool.release(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release must be positive");
  }

  @ParameterizedTest(name = "[{index}] quantity={0}")
  @ValueSource(ints = {0, -1})
  @DisplayName("補貨時應拒絕非正數 quantity")
  void rejectsNonPositiveReplenishQuantity(int quantity) {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 5, 0L);

    assertThatThrownBy(() -> stockPool.replenish(quantity))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to replenish must be positive");
  }

  @Test
  @DisplayName("釋放量超過已預留量時應拒絕且保持原狀態")
  void rejectsReleaseThatExceedsReservedQuantity() {
    StockPool stockPool = new StockPool(1L, "SKU-1", 10, 3, 0L);

    assertThatThrownBy(() -> stockPool.release(4))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Quantity to release cannot exceed reserved quantity");

    // 失敗的 release 不得偷偷將 reserved quantity 歸零。
    assertThat(stockPool.getReservedQuantity()).isEqualTo(3);
    assertThat(stockPool.availableToPromise()).isEqualTo(7);
  }
}
