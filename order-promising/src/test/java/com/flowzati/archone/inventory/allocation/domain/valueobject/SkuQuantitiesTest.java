package com.flowzati.archone.inventory.allocation.domain.valueobject;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("SkuQuantities：SKU 對數量的映射")
class SkuQuantitiesTest {

  @Nested
  @DisplayName("covers：每一個 SKU 都要蓋得住")
  class Covers {

    @Test
    @DisplayName("每一個 SKU 都夠時成立")
    void holdsWhenEverySkuIsCovered() {
      SkuQuantities available = SkuQuantities.of(Map.of("SKU-1", 10, "SKU-2", 5));

      assertThat(available.covers(SkuQuantities.of(Map.of("SKU-1", 10, "SKU-2", 5)))).isTrue();
    }

    @Test
    @DisplayName("只有一個 SKU 不夠就不成立——判準是全稱而不是存在")
    void failsWhenAnySingleSkuFallsShort() {
      SkuQuantities available = SkuQuantities.of(Map.of("SKU-1", 100, "SKU-2", 3));

      assertThat(available.covers(SkuQuantities.of(Map.of("SKU-1", 10, "SKU-2", 5)))).isFalse();
    }

    @Test
    @DisplayName("缺少的鍵視為 0——「沒有這個 SKU 的額度」與「額度是 0」對可滿足性是同一件事")
    void treatsAMissingKeyAsZero() {
      SkuQuantities available = SkuQuantities.of(Map.of("SKU-1", 100));

      assertThat(available.covers(SkuQuantities.of(Map.of("SKU-2", 1)))).isFalse();
      assertThat(available.covers(SkuQuantities.of(Map.of("SKU-2", 0)))).isTrue();
    }
  }

  @Nested
  @DisplayName("minus：扣掉之後剩下的額度")
  class Minus {

    @Test
    @DisplayName("逐個 SKU 扣，沒被扣到的維持原值")
    void subtractsPerSkuAndLeavesTheRestAlone() {
      SkuQuantities remaining = SkuQuantities.of(Map.of("SKU-1", 10, "SKU-2", 5))
          .minus(SkuQuantities.of(Map.of("SKU-1", 4)));

      assertThat(remaining.quantityOf("SKU-1")).isEqualTo(6);
      assertThat(remaining.quantityOf("SKU-2")).isEqualTo(5);
    }

    @Test
    @DisplayName("任何一個 SKU 扣成負的即拋錯——那代表呼叫端沒先 covers 就扣")
    void refusesToGoNegative() {
      SkuQuantities available = SkuQuantities.of(Map.of("SKU-1", 3));

      assertThatThrownBy(() -> available.minus(SkuQuantities.of(Map.of("SKU-1", 4))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("SKU-1");
    }

    @Test
    @DisplayName("不就地扣減——試算不得在原來的額度上留下痕跡")
    void leavesTheReceiverUntouched() {
      SkuQuantities available = SkuQuantities.of(Map.of("SKU-1", 10));

      // 挑單政策要在「試算這張單配不配得下」與「真的把額度扣掉」之間分開；就地扣減會讓一次
      // 失敗的試算把後面每一張單的判斷都算錯。
      available.minus(SkuQuantities.of(Map.of("SKU-1", 4)));

      assertThat(available.quantityOf("SKU-1")).isEqualTo(10);
    }
  }

  @Test
  @DisplayName("missingFrom 應列出每一個不足的 SKU 與各差幾件")
  void namesEverySkuThatFallsShort() {
    SkuQuantities demand = SkuQuantities.of(Map.of("SKU-1", 10, "SKU-2", 5, "SKU-3", 4));

    SkuQuantities missing =
        demand.missingFrom(SkuQuantities.of(Map.of("SKU-1", 100, "SKU-2", 3, "SKU-3", 1)));

    assertThat(missing.asMap()).containsExactlyInAnyOrderEntriesOf(
        Map.of("SKU-2", 2, "SKU-3", 3));
  }

  @Test
  @DisplayName("負數量應在建構時就被拒絕")
  void rejectsNegativeQuantities() {
    assertThatThrownBy(() -> SkuQuantities.of(Map.of("SKU-1", -1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
