package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.allocation.domain.model.StockFixtures;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AllocationPlan：取用計畫與缺口")
class AllocationPlanTest {

  private static final LocalDate FAR_EXPIRY = LocalDate.of(2027, 1, 31);

  @Test
  @DisplayName("可行的計畫沒有缺口")
  void aFeasiblePlanIsShortOfNothing() {
    AllocationPlan plan = AllocationPlan.feasible(List.of(pick(3)));

    assertThat(plan.isFeasible()).isTrue();
    assertThat(plan.shortfall().isEmpty()).isTrue();
  }

  @Test
  @DisplayName("不可行的計畫說得出每個 SKU 差幾件")
  void anInfeasiblePlanNamesWhatIsMissing() {
    AllocationPlan plan =
        AllocationPlan.shortOf(SkuQuantities.of(Map.of("SKU-1", 2, "SKU-2", 3)));

    // 改動前配不到是以「空的 picks 清單」表達——那是隱含約定（空清單也可以讀成「不需要任何
    // 批」），而且丟掉了「哪個 SKU 差幾件」這個操作上必要的資訊。
    assertThat(plan.isFeasible()).isFalse();
    assertThat(plan.shortfall().asMap()).containsExactlyInAnyOrderEntriesOf(
        Map.of("SKU-1", 2, "SKU-2", 3));
  }

  @Test
  @DisplayName("不可行卻帶著取用的計畫應被拒絕——湊不滿就一批都不取")
  void refusesAnInfeasiblePlanThatCarriesPicks() {
    // 留著半套的 picks 會讓呼叫端有機會「就先扣這些吧」，而那正是 ship-complete 禁止的事。
    assertThatThrownBy(() -> new AllocationPlan(
        List.of(pick(3)), SkuQuantities.of(Map.of("SKU-1", 2))))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @DisplayName("沒說出缺口的「不可行」應被拒絕")
  void refusesAnInfeasiblePlanThatNamesNothing() {
    assertThatThrownBy(() -> AllocationPlan.shortOf(SkuQuantities.empty()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static BatchPick pick(int quantity) {
    return new BatchPick(
        UUID.randomUUID(),
        StockFixtures.batchExpiringOn("SKU-1", FAR_EXPIRY, 100, 0),
        quantity);
  }
}
