package com.flowzati.archone.stock.movement.domain.aggregate;

import com.flowzati.archone.stock.movement.domain.type.PickingState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.foundation.identity.IdGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("StockPicking 狀態")
class StockPickingTest {

  @Test
  @DisplayName("作業單建立即確認，配貨後可執行，完成後不得取消")
  void followsTheReachableWarehouseLifecycle() {
    StockPicking picking = confirmedPicking();

    assertThat(picking.state()).isEqualTo(PickingState.CONFIRMED);
    assertThat(picking.assign()).isTrue();
    assertThat(picking.state()).isEqualTo(PickingState.ASSIGNED);
    assertThat(picking.complete()).isTrue();
    assertThat(picking.state()).isEqualTo(PickingState.DONE);
    assertThatThrownBy(picking::cancel)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("completed picking");
  }

  @Test
  @DisplayName("等待庫存或已配貨的作業單都可以取消")
  void cancelsBeforeCompletion() {
    StockPicking waiting = confirmedPicking();
    StockPicking ready = confirmedPicking();
    ready.assign();

    assertThat(waiting.cancel()).isTrue();
    assertThat(ready.cancel()).isTrue();
    assertThat(waiting.state()).isEqualTo(PickingState.CANCELLED);
    assertThat(ready.state()).isEqualTo(PickingState.CANCELLED);
  }

  @Test
  @DisplayName("不能跳過配貨直接完成")
  void rejectsCompletionBeforeAssignment() {
    assertThatThrownBy(() -> confirmedPicking().complete())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Only an assigned picking");
  }

  private StockPicking confirmedPicking() {
    return StockPicking.confirmedOutbound(
        IdGenerator.nextId(), IdGenerator.nextId(), IdGenerator.nextId(), IdGenerator.nextId(),
        IdGenerator.nextId(), IdGenerator.nextId(),
        java.time.Instant.parse("2026-08-01T08:00:00Z"), 50);
  }
}
