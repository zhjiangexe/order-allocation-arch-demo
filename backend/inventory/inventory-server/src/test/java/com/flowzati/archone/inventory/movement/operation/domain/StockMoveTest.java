package com.flowzati.archone.inventory.movement.operation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockMove 狀態")
class StockMoveTest {

    private static final Instant CREATED_AT = Instant.parse("2026-07-24T01:00:00Z");
    private static final Instant ASSIGNED_AT = CREATED_AT.plusSeconds(1);
    private static final Instant COMPLETED_AT = ASSIGNED_AT.plusSeconds(1);

    @Test
    @DisplayName("等待或已配貨的 movement 可以取消")
    void canCancelBeforeCompletion() {
        StockMove waiting = confirmedMove();
        StockMove assigned = confirmedMove();
        assigned.assign(ASSIGNED_AT);

        assertThat(waiting.canCancel()).isTrue();
        assertThat(assigned.canCancel()).isTrue();
    }

    @Test
    @DisplayName("完成或已取消的 movement 不可再次進入取消流程")
    void cannotCancelAfterTerminalState() {
        StockMove completed = confirmedMove();
        completed.assign(ASSIGNED_AT);
        completed.complete(COMPLETED_AT);
        StockMove cancelled = confirmedMove();
        cancelled.cancel();

        assertThat(completed.canCancel()).isFalse();
        assertThat(completed.isCancelled()).isFalse();
        assertThat(cancelled.canCancel()).isFalse();
        assertThat(cancelled.isCancelled()).isTrue();
    }

    @Test
    @DisplayName("釋放 reservation 清除 assigned time 並保留同一 move identity")
    void unassignsTheExistingMove() {
        StockMove move = confirmedMove();

        move.assign(ASSIGNED_AT);
        assertThat(move.unassign()).isTrue();
        assertThat(move.getState()).isEqualTo(MoveState.CONFIRMED);
        assertThat(move.getAssignedAt()).isNull();
        assertThat(move.getSourceLineId()).isEqualTo("LINE-1");
        assertThat(move.getLineSequence()).isEqualTo(1);

        move.cancel();
        assertThatThrownBy(move::unassign).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("registration replay 比較 line identity、順序與 quantity，不比較 mutable state")
    void comparesImmutableMovementContent() {
        StockMove original = confirmedMove();
        StockMove retry = confirmedMove();
        StockMove drifted = StockMove.confirmedForSourceLine(
                IdGenerator.nextId(),
                original.getStockOperationId(),
                original.getOwnerId(),
                original.getSkuCode(),
                original.getFromLocationId(),
                original.getToLocationId(),
                "LINE-1",
                1,
                2,
                CREATED_AT);

        original.assign(ASSIGNED_AT);

        assertThat(original.hasSameRegistrationContent(retry)).isTrue();
        assertThat(original.hasSameRegistrationContent(drifted)).isFalse();
    }

    private StockMove confirmedMove() {
        return StockMove.confirmedForSourceLine(
                new java.util.UUID(0, 10),
                new java.util.UUID(0, 11),
                new java.util.UUID(0, 12),
                "SKU-1",
                new java.util.UUID(0, 13),
                new java.util.UUID(0, 14),
                "LINE-1",
                1,
                1,
                CREATED_AT);
    }
}
