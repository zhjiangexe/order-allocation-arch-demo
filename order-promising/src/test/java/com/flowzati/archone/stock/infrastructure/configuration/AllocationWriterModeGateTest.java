package com.flowzati.archone.stock.infrastructure.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AllocationWriterModeGateTest {

  @Test
  void shouldAcceptOnlyTheFinalDemandWriterMode() {
    assertThat(new AllocationWriterModeGate(" demand ").mode()).isEqualTo("demand");
    assertThatThrownBy(() -> new AllocationWriterModeGate("legacy"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("pause and drain");
  }
}
