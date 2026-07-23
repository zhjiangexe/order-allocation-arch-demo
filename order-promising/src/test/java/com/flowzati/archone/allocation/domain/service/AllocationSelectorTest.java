package com.flowzati.archone.allocation.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.ordering.domain.model.Order;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AllocationSelectorTest {

  @Test
  @DisplayName("應由 Factory 建立 Policy 專用 Context，再執行純 selection algorithm")
  void createsTypedContextBeforeInvokingPolicy() {
    Instant decisionAt = Instant.parse("2026-07-24T02:00:00Z");
    AllocationRequest request = new AllocationRequest(1L, "SKU-1", 5, decisionAt);
    Order order = Order.place(UUID.randomUUID(), "SKU-1", 3, decisionAt.minusSeconds(1));
    order.releaseDomainEvents();

    AllocationContextFactory<TestAllocationContext> contextFactory = source ->
        new TestAllocationContext(source.availableToPromise(), source.decisionAt());
    AllocationPolicy<TestAllocationContext> policy = (candidates, context) -> {
      assertThat(context.availableToPromise()).isEqualTo(5);
      assertThat(context.decisionAt()).isEqualTo(decisionAt);
      return candidates;
    };
    AllocationSelector selector =
        AllocationSelector.contextual(policy, contextFactory);

    List<Order> selected = selector.selectOrders(List.of(order), request);

    assertThat(selected).containsExactly(order);
  }

  private record TestAllocationContext(
      int availableToPromise,
      Instant decisionAt
  ) implements AllocationContext {
  }
}
