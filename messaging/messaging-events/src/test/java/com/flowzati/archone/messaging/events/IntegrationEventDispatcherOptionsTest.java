package com.flowzati.archone.messaging.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntegrationEventDispatcherOptionsTest {

  @Test
  void isStrictByDefault() {
    IntegrationEventDispatcherOptions options = IntegrationEventDispatcherOptions.strict();

    assertThat(options.unhandledEventPolicy()).isEqualTo(UnhandledEventPolicy.FAIL);
    assertThat(options.unhandledEventObserver()).isEmpty();
  }

  @Test
  void cannotConfigureIgnoreWithoutAnObserver() {
    assertThatThrownBy(() -> IntegrationEventDispatcherOptions.builder()
        .ignoreUnhandledEventsWith(null))
        .isInstanceOf(NullPointerException.class)
        .hasMessageContaining("observer");
  }
}
