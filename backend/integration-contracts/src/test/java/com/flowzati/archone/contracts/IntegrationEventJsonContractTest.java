package com.flowzati.archone.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.OutboundMovementsCompletedIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.inventory.v1.StockAvailabilityIncreasedIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderCancelledIntegrationEvent;
import com.flowzati.archone.contracts.ordering.v1.OrderPlacedIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.events.IntegrationEvent;
import java.io.InputStream;
import java.time.Instant;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.DateTimeFeature;

class IntegrationEventJsonContractTest {

    private static final UUID EVENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID LOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final UUID ALLOCATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000006");
    private static final UUID ORDER_LINE_ID = UUID.fromString("00000000-0000-0000-0000-000000000007");
    private static final UUID MOVE_ID = UUID.fromString("00000000-0000-0000-0000-000000000008");
    private static final UUID SHIPMENT_ID = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final Instant OCCURRED_AT = Instant.parse("2026-08-07T00:00:00Z");

    private final ObjectMapper objectMapper = new ObjectMapper()
            .rebuild()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build();

    @ParameterizedTest(name = "{0}")
    @MethodSource("contracts")
    void matchesGoldenPayloadAndRoundTrips(
            String resource, IntegrationEvent event, Class<? extends IntegrationEvent> eventClass) throws Exception {
        try (InputStream expectedJson = getClass().getResourceAsStream("/contracts/" + resource)) {
            assertThat(expectedJson).as("golden contract %s", resource).isNotNull();
            assertThat(objectMapper.readTree(objectMapper.writeValueAsString(event)))
                    .isEqualTo(objectMapper.readTree(expectedJson));
        }

        IntegrationEvent restored = objectMapper.readValue(objectMapper.writeValueAsString(event), eventClass);
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
                        "stock-availability-increased-v1.json",
                        new StockAvailabilityIncreasedIntegrationEvent(
                                EVENT_ID, OWNER_ID, FACILITY_ID, LOCATION_ID, "SKU-1", 3),
                        StockAvailabilityIncreasedIntegrationEvent.class),
                Arguments.of(
                        "order-allocation-committed-v1.json",
                        new OrderAllocationCommittedIntegrationEvent(
                                EVENT_ID,
                                ALLOCATION_ID,
                                ORDER_ID,
                                OWNER_ID,
                                FACILITY_ID,
                                java.util.List.of(new OrderAllocationCommittedIntegrationEvent.AllocationLine(
                                        ORDER_LINE_ID, MOVE_ID, "SKU-1", LOCATION_ID, 3)),
                                OCCURRED_AT.plusSeconds(3600),
                                80,
                                OCCURRED_AT),
                        OrderAllocationCommittedIntegrationEvent.class),
                Arguments.of(
                        "shipment-handed-over-v1.json",
                        new ShipmentHandedOverIntegrationEvent(
                                EVENT_ID,
                                SHIPMENT_ID,
                                ALLOCATION_ID,
                                ORDER_ID,
                                java.util.List.of(MOVE_ID),
                                OCCURRED_AT),
                        ShipmentHandedOverIntegrationEvent.class),
                Arguments.of(
                        "outbound-movements-completed-v1.json",
                        new OutboundMovementsCompletedIntegrationEvent(
                                EVENT_ID,
                                ALLOCATION_ID,
                                ORDER_ID,
                                SHIPMENT_ID,
                                java.util.List.of(MOVE_ID),
                                OCCURRED_AT),
                        OutboundMovementsCompletedIntegrationEvent.class));
    }
}
