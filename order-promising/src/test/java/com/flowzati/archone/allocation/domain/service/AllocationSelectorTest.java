package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.allocation.domain.service.selector.AllocationContext;
import com.flowzati.archone.allocation.domain.service.selector.AllocationContextFactory;
import com.flowzati.archone.allocation.domain.service.selector.AllocationPolicy;
import com.flowzati.archone.allocation.domain.service.selector.AllocationSelector;
import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.testsupport.DemandFixtures;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationSelectorTest {

  @Test
  @DisplayName("應由 Factory 建立 Policy 專用 Context，再執行純 selection algorithm")
  void createsTypedContextBeforeInvokingPolicy() {
    Instant decisionAt = Instant.parse("2026-07-24T02:00:00Z");
    AllocationRequest request =
        new AllocationRequest(SkuQuantities.of(Map.of("SKU-1", 5)), decisionAt);
    Demand demand = DemandFixtures.demand(
        UUID.randomUUID(), "SKU-1", 3);

    AllocationContextFactory<TestAllocationContext> contextFactory = source ->
        new TestAllocationContext(source.availableBySku(), source.decisionAt());
    AllocationPolicy<TestAllocationContext> policy = (candidates, context) -> {
      assertThat(context.availableBySku().quantityOf("SKU-1")).isEqualTo(5);
      assertThat(context.decisionAt()).isEqualTo(decisionAt);
      return candidates;
    };
    AllocationSelector selector =
        AllocationSelector.contextual(policy, contextFactory);

    List<Demand> selected = selector.select(List.of(demand), request);

    assertThat(selected).containsExactly(demand);
  }

  private record TestAllocationContext(
      SkuQuantities availableBySku,
      Instant decisionAt
  ) implements AllocationContext {
  }
}
