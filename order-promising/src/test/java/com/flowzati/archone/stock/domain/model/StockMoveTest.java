package com.flowzati.archone.stock.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.foundation.identity.IdGenerator;
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

  private StockMove confirmedMove() {
    return StockMove.confirmed(
        IdGenerator.nextId(),
        IdGenerator.nextId(),
        IdGenerator.nextId(),
        "SKU-1",
        IdGenerator.nextId(),
        IdGenerator.nextId(),
        IdGenerator.nextId(),
        1,
        CREATED_AT);
  }
}
