package com.flowzati.archone.inventory.movement.operation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockOperation;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockOperation 狀態")
class StockOperationTest {

    private static final Instant ENQUEUED_AT = Instant.parse("2026-08-01T07:00:00Z");
    private static final Instant DISPATCH_BY = Instant.parse("2026-08-01T08:00:00Z");

    @Test
    @DisplayName("作業單建立即確認，配貨後可執行，完成後不得取消")
    void followsTheReachableWarehouseLifecycle() {
        StockOperation operation = confirmedOperation();

        assertThat(operation.state()).isEqualTo(StockOperationState.CONFIRMED);
        assertThat(operation.assign()).isTrue();
        assertThat(operation.state()).isEqualTo(StockOperationState.ASSIGNED);
        assertThat(operation.complete()).isTrue();
        assertThat(operation.state()).isEqualTo(StockOperationState.DONE);
        assertThatThrownBy(operation::cancel)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed operation");
    }

    @Test
    @DisplayName("等待庫存或已配貨的作業單都可以取消")
    void cancelsBeforeCompletion() {
        StockOperation waiting = confirmedOperation();
        StockOperation ready = confirmedOperation();
        ready.assign();

        assertThat(waiting.cancel()).isTrue();
        assertThat(ready.cancel()).isTrue();
        assertThat(waiting.state()).isEqualTo(StockOperationState.CANCELLED);
        assertThat(ready.state()).isEqualTo(StockOperationState.CANCELLED);
    }

    @Test
    @DisplayName("不能跳過配貨直接完成")
    void rejectsCompletionBeforeAssignment() {
        assertThatThrownBy(() -> confirmedOperation().complete())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an assigned operation");
    }

    @Test
    @DisplayName("釋放 reservation 只讓 ASSIGNED 作業回到 CONFIRMED")
    void unassignsOnlyReversibleOperation() {
        StockOperation operation = confirmedOperation();

        assertThat(operation.unassign()).isFalse();
        operation.assign();
        assertThat(operation.unassign()).isTrue();
        assertThat(operation.state()).isEqualTo(StockOperationState.CONFIRMED);

        operation.assign();
        operation.complete();
        assertThatThrownBy(operation::unassign)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Only an assigned operation");
    }

    @Test
    @DisplayName("source identity、policy 與 enqueue time 是 registration replay 的 immutable content")
    void comparesOnlyImmutableRegistrationContent() {
        StockOperation original = confirmedOperation();
        StockOperation equalRetry =
                canonicalOperation(IdGenerator.nextId(), StockOperationSource.primaryOrder("ORDER-1"), 50);
        StockOperation drifted =
                canonicalOperation(IdGenerator.nextId(), StockOperationSource.primaryOrder("ORDER-1"), 51);

        original.assign();

        assertThat(original.source()).isEqualTo(StockOperationSource.primaryOrder("ORDER-1"));
        assertThat(original.assignmentPolicy()).isEqualTo(MovementAssignmentPolicy.SHIP_COMPLETE);
        assertThat(original.enqueuedAt()).isEqualTo(ENQUEUED_AT);
        assertThat(original.hasSameRegistrationContent(equalRetry)).isTrue();
        assertThat(original.hasSameRegistrationContent(drifted)).isFalse();
    }

    private StockOperation confirmedOperation() {
        return canonicalOperation(IdGenerator.nextId(), StockOperationSource.primaryOrder("ORDER-1"), 50);
    }

    private StockOperation canonicalOperation(
            java.util.UUID stockOperationId, StockOperationSource source, int releasePriority) {
        return StockOperation.confirmedStockConsumption(
                stockOperationId,
                new java.util.UUID(0, 1),
                StockOperationDirection.OUTBOUND,
                new java.util.UUID(0, 2),
                source,
                new java.util.UUID(0, 3),
                new java.util.UUID(0, 4),
                MovementAssignmentPolicy.SHIP_COMPLETE,
                ENQUEUED_AT,
                DISPATCH_BY,
                releasePriority);
    }
}
