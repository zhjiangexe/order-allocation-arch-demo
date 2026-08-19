package com.flowzati.archone.inventory.allocation.domain.aggregate;

import com.flowzati.archone.inventory.allocation.domain.type.AllocationCancellationState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Allocation cancellation operation")
class AllocationCancellationOperationTest {

  private static final Instant STARTED = Instant.parse("2026-08-18T05:00:00Z");

  @Test
  @DisplayName("external confirmation 與 local completion 是不同 checkpoint")
  void resumesLocalCompletionAfterExternalConfirmation() {
    AllocationCancellationOperation operation = AllocationCancellationOperation.start(
        UUID.randomUUID(), UUID.randomUUID(), STARTED);

    operation.confirmExternally(STARTED.plusSeconds(1));
    assertThat(operation.state()).isEqualTo(AllocationCancellationState.EXTERNAL_CONFIRMED);

    operation.completeLocally(STARTED.plusSeconds(2));
    assertThat(operation.state()).isEqualTo(AllocationCancellationState.COMPLETED);
  }

  @Test
  @DisplayName("rejected operation 不因外部狀態後來改變而重新決策")
  void keepsARejectedDecision() {
    AllocationCancellationOperation operation = AllocationCancellationOperation.start(
        UUID.randomUUID(), UUID.randomUUID(), STARTED);
    operation.rejectExternally(STARTED.plusSeconds(1));

    assertThatThrownBy(() -> operation.confirmExternally(STARTED.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be reevaluated");
    assertThat(operation.state()).isEqualTo(AllocationCancellationState.EXTERNAL_REJECTED);
  }
}
