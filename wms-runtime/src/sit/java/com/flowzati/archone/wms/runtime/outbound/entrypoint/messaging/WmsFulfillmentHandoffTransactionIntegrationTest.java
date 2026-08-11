package com.flowzati.archone.wms.runtime.outbound.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowzati.archone.contracts.fulfillment.v1.AllocationCommittedForFulfillmentIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentChannels;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.wms.outbound.domain.model.ShipmentStatus;
import com.flowzati.archone.wms.outbound.domain.repository.ShipmentRepository;
import com.flowzati.archone.wms.runtime.WmsRuntimeApplication;
import com.flowzati.archone.wms.runtime.testsupport.WmsPostgreSQLTestConfiguration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
    classes = WmsRuntimeApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(WmsPostgreSQLTestConfiguration.class)
class WmsFulfillmentHandoffTransactionIntegrationTest {

  @Autowired
  private ControllableMessageConsumerImplementation transport;

  @Autowired
  private IntegrationEventSerializer serializer;

  @Autowired
  private ShipmentRepository shipmentRepository;

  @Autowired
  private JdbcTemplate jdbcTemplate;

  @AfterEach
  void clearDatabase() {
    jdbcTemplate.update("DELETE FROM event_inbox");
    jdbcTemplate.update("DELETE FROM wms_pick_tasks");
    jdbcTemplate.update("DELETE FROM wms_shipment_lines");
    jdbcTemplate.update("DELETE FROM wms_shipments");
  }

  @Test
  void commitsInboxAndShipmentOnceForADuplicateDelivery() {
    UUID eventId = UUID.randomUUID();
    UUID allocationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    var event = event(eventId, allocationId, orderId);

    emit(event);
    emit(event);

    assertThat(inboxCount(eventId)).isOne();
    assertThat(count("wms_shipments")).isOne();
    assertThat(count("wms_shipment_lines")).isOne();
    assertThat(shipmentRepository.findByAllocationId(allocationId)).hasValueSatisfying(shipment -> {
      assertThat(shipment.orderId()).isEqualTo(orderId);
      assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);
      assertThat(shipment.releasePriority()).isEqualTo(80);
      assertThat(shipment.lines()).hasSize(1);
    });
  }

  @Test
  void keepsBusinessIdempotencyWhenTheSameAllocationIsRepublishedWithANewEventId() {
    UUID allocationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID firstEventId = UUID.randomUUID();
    UUID secondEventId = UUID.randomUUID();
    var first = event(firstEventId, allocationId, orderId);

    emit(first);
    emit(copyWithEventId(first, secondEventId));

    assertThat(inboxCount(firstEventId)).isOne();
    assertThat(inboxCount(secondEventId)).isOne();
    assertThat(count("wms_shipments")).isOne();
    assertThat(shipmentRepository.findByOrderId(orderId)).hasSize(1);
  }

  private void emit(AllocationCommittedForFulfillmentIntegrationEvent event) {
    transport.emit(
        WmsEventSubscriptions.FULFILLMENT_HANDOFF,
        FulfillmentChannels.FULFILLMENT_HANDOFFS,
        message(event),
        1);
  }

  private Message message(AllocationCommittedForFulfillmentIntegrationEvent event) {
    return MessageBuilder.withPayload(serializer.serialize(event))
        .withId(event.getEventId())
        .withType(event.eventType())
        .withPartitionId(event.getOrderId().toString())
        .withMessageDate(event.getCommittedAt())
        .withHeader(EventMessageHeaders.EVENT_TYPE, event.eventType())
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "StockPicking")
        .withHeader(EventMessageHeaders.EVENT_AGGREGATE_ID, event.getAllocationId().toString())
        .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
        .build();
  }

  private AllocationCommittedForFulfillmentIntegrationEvent event(
      UUID eventId,
      UUID allocationId,
      UUID orderId
  ) {
    return new AllocationCommittedForFulfillmentIntegrationEvent(
        eventId,
        allocationId,
        orderId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        List.of(new AllocationCommittedForFulfillmentIntegrationEvent.AllocationLine(
            UUID.randomUUID(), UUID.randomUUID(), "SKU-1", UUID.randomUUID(), 3)),
        Instant.parse("2026-08-12T08:00:00Z"),
        80,
        Instant.parse("2026-08-11T01:00:00Z"));
  }

  private AllocationCommittedForFulfillmentIntegrationEvent copyWithEventId(
      AllocationCommittedForFulfillmentIntegrationEvent event,
      UUID eventId
  ) {
    return new AllocationCommittedForFulfillmentIntegrationEvent(
        eventId,
        event.getAllocationId(),
        event.getOrderId(),
        event.getOwnerId(),
        event.getFacilityId(),
        event.getLines(),
        event.getDispatchBy(),
        event.getReleasePriority(),
        event.getCommittedAt());
  }

  private int inboxCount(UUID eventId) {
    return jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM event_inbox WHERE subscriber_id = ? AND event_id = ?",
        Integer.class,
        WmsEventSubscriptions.FULFILLMENT_HANDOFF,
        eventId);
  }

  private int count(String table) {
    return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
  }
}
