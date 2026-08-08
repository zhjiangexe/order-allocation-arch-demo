package com.flowzati.archone.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.BackorderCreatedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocatedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class IntegrationEventJsonContractTest {

  private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
  private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
  private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
  private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T00:00:00Z");

  private final ObjectMapper objectMapper = new ObjectMapper()
      .findAndRegisterModules()
      .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  @ParameterizedTest(name = "{0}")
  @MethodSource("contracts")
  void matchesGoldenPayloadAndRoundTrips(
      String resource,
      IntegrationEvent event,
      Class<? extends IntegrationEvent> eventClass
  ) throws Exception {
    try (InputStream expectedJson = getClass().getResourceAsStream("/contracts/" + resource)) {
      assertThat(expectedJson).as("golden contract %s", resource).isNotNull();
      assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event)))
          .isEqualTo(objectMapper.readTree(expectedJson));
    }

    IntegrationEvent restored = objectMapper.readValue(
        objectMapper.writeValueAsString(event), eventClass);
    assertThat(restored.getEventId()).isEqualTo(event.getEventId());
    assertThat(restored.eventType()).isEqualTo(event.eventType());
  }

  private static Stream<Arguments> contracts() {
    return Stream.of(
        Arguments.of(
            "order-placed-v1.json",
            new OrderPlacedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT),
            OrderPlacedIntegrationEvent.class),
        Arguments.of(
            "order-cancelled-v1.json",
            new OrderCancelledIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT),
            OrderCancelledIntegrationEvent.class),
        Arguments.of(
            "order-allocated-v1.json",
            new OrderAllocatedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT),
            OrderAllocatedIntegrationEvent.class),
        Arguments.of(
            "backorder-created-v1.json",
            new BackorderCreatedIntegrationEvent(EVENT_ID, ORDER_ID, OCCURRED_AT),
            BackorderCreatedIntegrationEvent.class),
        Arguments.of(
            "stock-availability-increased-v1.json",
            new StockAvailabilityIncreasedIntegrationEvent(
                EVENT_ID, OWNER_ID, FACILITY_ID, LOCATION_ID, "SKU-1", 3),
            StockAvailabilityIncreasedIntegrationEvent.class));
  }
}
