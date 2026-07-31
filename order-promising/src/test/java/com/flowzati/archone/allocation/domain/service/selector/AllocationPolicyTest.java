package com.flowzati.archone.allocation.domain.service.selector;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.service.AllocationRequest;
import com.flowzati.archone.allocation.domain.service.SkuQuantities;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.testsupport.DemandFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("挑單政策在多 SKU 之下的行為")
class AllocationPolicyTest {

  private static final Instant NOW = Instant.parse("2026-07-24T10:00:00Z");

  @Nested
  @DisplayName("嚴格 FIFO")
  class StrictFifo {

    private final AllocationSelector selector = AllocationSelector.strictFifo();

    @Test
    @DisplayName("隊首因為別的 SKU 卡住時，後面只要充足 SKU 的單也不得插隊")
    void blocksTheQueueWhenTheHeadIsShortOfAnySku() {
      Demand head = demand(DemandFixtures.line("SKU-A", 1), DemandFixtures.line("SKU-B", 1));
      Demand behind = demand(DemandFixtures.line("SKU-A", 1));

      List<Demand> selected = selector.select(
          List.of(head, behind), request(Map.of("SKU-A", 100, "SKU-B", 0)));

      // head-of-line blocking 就是先來先服務的內容。改成 continue 會讓隊首在後面不斷有小單時
      // 永遠等下去；真要緩解，做法是**替隊首保留額度**（佔住它需要的量、後面的用剩下的），
      // 順序因此仍然成立——而不是放棄順序。
      assertThat(selected).isEmpty();
    }

    @Test
    @DisplayName("判準是「任一 SKU 不足」而不是「某一個 SKU 不足」")
    void stopsWhenAnySingleSkuIsShort() {
      Demand first = demand(DemandFixtures.line("SKU-A", 1));
      Demand second = demand(DemandFixtures.line("SKU-A", 1), DemandFixtures.line("SKU-B", 5));
      Demand third = demand(DemandFixtures.line("SKU-A", 1));

      List<Demand> selected = selector.select(
          List.of(first, second, third), request(Map.of("SKU-A", 100, "SKU-B", 1)));

      assertThat(selected).containsExactly(first);
    }

    @Test
    @DisplayName("每一張單都蓋得住時全部選上，且額度逐張扣減")
    void selectsEveryCandidateAndSpendsTheAllowanceAsItGoes() {
      Demand first = demand(DemandFixtures.line("SKU-A", 6));
      Demand second = demand(DemandFixtures.line("SKU-A", 4));
      Demand third = demand(DemandFixtures.line("SKU-A", 1));

      List<Demand> selected = selector.select(
          List.of(first, second, third), request(Map.of("SKU-A", 10)));

      // 第三張要的 1 件在扣掉前兩張之後已經沒有了——少了逐張扣減，三張都會被選上。
      assertThat(selected).containsExactly(first, second);
    }
  }

  @Nested
  @DisplayName("最大化完成張數")
  class MaximizeFulfilledOrders {

    private final AllocationSelector selector = AllocationSelector.maximizeFulfilledOrders();

    @Test
    @DisplayName("排序鍵是整籃的總件數，不是涉及的 SKU 數")
    void ordersByTotalUnitsAcrossTheWholeBasket() {
      Demand bulky = demand(DemandFixtures.line("SKU-A", 8));
      Demand spread = demand(
          DemandFixtures.line("SKU-A", 1),
          DemandFixtures.line("SKU-B", 1),
          DemandFixtures.line("SKU-C", 1));

      List<Demand> selected = selector.select(
          List.of(bulky, spread), request(Map.of("SKU-A", 8, "SKU-B", 1, "SKU-C", 1)));

      // 兩種排序鍵在這裡導向相反的結果：以總件數排，spread（3 件）先，吃掉 A 的 1 件之後
      // bulky 的 8 件湊不齊；以 SKU 數排，bulky（1 個 SKU）先，吃光 A 之後 spread 湊不齊。
      // 選總件數的理由是它與目標同一個單位——庫存以件計，餵飽一張單付出的代價就是它的件數。
      assertThat(selected).containsExactly(spread);
    }

    @Test
    @DisplayName("配不下的單跳過而不是停下——這正是它與嚴格 FIFO 的分歧")
    void skipsRatherThanStops() {
      Demand tooBig = demand(DemandFixtures.line("SKU-A", 100));
      Demand small = demand(DemandFixtures.line("SKU-A", 1));

      List<Demand> selected =
          selector.select(List.of(tooBig, small), request(Map.of("SKU-A", 5)));

      assertThat(selected).containsExactly(small);
    }
  }

  private static AllocationRequest request(Map<String, Integer> availableBySku) {
    return new AllocationRequest(SkuQuantities.of(availableBySku), NOW);
  }

  private static Demand demand(com.flowzati.archone.allocation.domain.model.DemandLine... lines) {
    return DemandFixtures.multiLineDemand(IdGenerator.nextId(), NOW.minusSeconds(10), lines);
  }
}
