package com.flowzati.archone.stock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Allocation demand aggregate")
class AllocationDemandTest {

  private static final UUID DEMAND_ID = uuid(1);
  private static final UUID OWNER_ID = uuid(2);
  private static final UUID FACILITY_ID = uuid(3);
  private static final UUID LOCATION_ID = uuid(4);
  private static final Instant NOW = Instant.parse("2026-08-18T05:00:00Z");

  @Test
  @DisplayName("transport 順序不影響 canonical line sequence 與 allocation-owned identity")
  void canonicalizesSourceLinesBeforeAssigningIdentity() {
    Queue<UUID> ids = new ArrayDeque<>(List.of(uuid(11), uuid(12)));

    AllocationDemand demand = AllocationDemand.accept(
        DEMAND_ID,
        SourceAllocationUnit.primaryOrder("order-1"),
        OWNER_ID,
        FACILITY_ID,
        LOCATION_ID,
        NOW.plusSeconds(3600),
        50,
        NOW,
        List.of(
            new AllocationDemandLineRequest("line-B", "SKU-2", 2),
            new AllocationDemandLineRequest("line-A", "SKU-1", 1)),
        ids::remove);

    assertThat(demand.lines()).extracting(AllocationDemandLine::sourceLineId)
        .containsExactly("line-A", "line-B");
    assertThat(demand.lines()).extracting(AllocationDemandLine::lineSequence)
        .containsExactly(1, 2);
    assertThat(demand.lines()).extracting(AllocationDemandLine::id)
        .containsExactly(uuid(11), uuid(12));
  }

  @Test
  @DisplayName("同一 allocation unit 內重複 source line id 應拒絕")
  void rejectsDuplicateSourceLineIds() {
    assertThatThrownBy(() -> demand(List.of(
        new AllocationDemandLineRequest("line-A", "SKU-1", 1),
        new AllocationDemandLineRequest("line-A", "SKU-2", 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Duplicate source line ID");
  }

  @Test
  @DisplayName("同 SKU checked aggregation 溢位時整筆拒絕")
  void rejectsOverflowingSkuAggregate() {
    assertThatThrownBy(() -> demand(List.of(
        new AllocationDemandLineRequest("line-A", "SKU-1", Integer.MAX_VALUE),
        new AllocationDemandLineRequest("line-B", "SKU-1", 1))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exceeds integer range");
  }

  @Test
  @DisplayName("allocation demand quantity 必須是 positive integer")
  void rejectsInvalidQuantity() {
    assertThatThrownBy(() -> new AllocationDemandLineRequest("line-A", "SKU-1", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  @DisplayName("allocation lifecycle 只有 pending、allocated、cancelled 且不得重新排隊")
  void enforcesAllocationOnlyLifecycle() {
    AllocationDemand pending = demand(List.of(
        new AllocationDemandLineRequest("line-A", "SKU-1", 1)));

    assertThat(pending.markAllocated()).isTrue();
    assertThat(pending.status()).isEqualTo(AllocationDemandStatus.ALLOCATED);
    assertThatThrownBy(pending::cancelPending)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("confirmed reversible execution cancellation");
    assertThat(pending.cancelAllocatedAfterExecutionStopped()).isTrue();
    assertThat(pending.status()).isEqualTo(AllocationDemandStatus.CANCELLED);
    assertThatThrownBy(pending::markAllocated)
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  @DisplayName("pending demand 可直接且冪等取消")
  void cancelsPendingDemandIdempotently() {
    AllocationDemand pending = demand(List.of(
        new AllocationDemandLineRequest("line-A", "SKU-1", 1)));

    assertThat(pending.cancelPending()).isTrue();
    assertThat(pending.cancelPending()).isFalse();
  }

  @Test
  @DisplayName("source identity 與單一 inventory scope 都是必要資料")
  void requiresCanonicalSourceAndOneScope() {
    assertThatThrownBy(() -> new SourceAllocationUnit(
        AllocationSourceType.ORDER, " ", SourceAllocationUnit.PRIMARY))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> AllocationDemand.accept(
        DEMAND_ID,
        SourceAllocationUnit.primaryOrder("order-1"),
        OWNER_ID,
        FACILITY_ID,
        null,
        NOW.plusSeconds(3600),
        50,
        NOW,
        List.of(new AllocationDemandLineRequest("line-A", "SKU-1", 1)),
        () -> uuid(11)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one inventory scope");
  }

  @Test
  @DisplayName("非 order source identity 不需要 order id")
  void supportsSourceAgnosticIdentity() {
    for (AllocationSourceType type : List.of(
        AllocationSourceType.TRANSFER,
        AllocationSourceType.REPLENISHMENT,
        AllocationSourceType.PRODUCTION,
        AllocationSourceType.MANUAL)) {
      AllocationDemand demand = AllocationDemand.accept(
          UUID.randomUUID(),
          new SourceAllocationUnit(type, type.name().toLowerCase() + "-1", "PRIMARY"),
          OWNER_ID,
          FACILITY_ID,
          LOCATION_ID,
          NOW.plusSeconds(3600),
          50,
          NOW,
          List.of(new AllocationDemandLineRequest("source-line", "SKU-1", 1)),
          UUID::randomUUID);

      assertThat(demand.source().sourceType()).isEqualTo(type);
    }
  }

  private static AllocationDemand demand(List<AllocationDemandLineRequest> lines) {
    return AllocationDemand.accept(
        DEMAND_ID,
        SourceAllocationUnit.primaryOrder("order-1"),
        OWNER_ID,
        FACILITY_ID,
        LOCATION_ID,
        NOW.plusSeconds(3600),
        50,
        NOW,
        lines,
        UUID::randomUUID);
  }

  private static UUID uuid(long value) {
    return new UUID(0L, value);
  }
}
