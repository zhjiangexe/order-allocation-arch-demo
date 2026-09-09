package com.flowzati.archone.wms.shipment.entrypoint.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.ArchoneApplication;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentCancelledIntegrationEvent;
import com.flowzati.archone.contracts.fulfillment.v1.ShipmentHandedOverIntegrationEvent;
import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.contracts.promising.v1.OrderAllocationCommittedIntegrationEvent;
import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageBuilder;
import com.flowzati.archone.messaging.events.EventMessageHeaders;
import com.flowzati.archone.messaging.events.IntegrationEventSerializer;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.testsupport.PostgreSQLTestConfiguration;
import com.flowzati.archone.wms.picking.application.store.PickingWorkStore;
import com.flowzati.archone.wms.picking.domain.type.PickTaskStatus;
import com.flowzati.archone.wms.process.application.invocation.SimulateWarehouseOperationsCommand;
import com.flowzati.archone.wms.process.application.usecase.ProcessDueShipmentsUsecase;
import com.flowzati.archone.wms.process.application.usecase.SimulateWarehouseOperationsUsecase;
import com.flowzati.archone.wms.shipment.application.invocation.CancelShipmentCommand;
import com.flowzati.archone.wms.shipment.application.store.ShipmentStore;
import com.flowzati.archone.wms.shipment.application.usecase.CancelShipmentUsecase;
import com.flowzati.archone.wms.shipment.domain.type.CancelShipmentStatus;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentCancellationState;
import com.flowzati.archone.wms.shipment.domain.type.ShipmentStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        classes = ArchoneApplication.class,
        properties = "spring.kafka.listener.auto-startup=false",
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Import(PostgreSQLTestConfiguration.class)
class WmsFulfillmentHandoffTransactionIntegrationTest {

    @Autowired
    private ControllableMessageConsumerImplementation transport;

    @Autowired
    private IntegrationEventSerializer serializer;

    @Autowired
    private ShipmentStore shipmentStore;

    @Autowired
    private PickingWorkStore pickingWorkStore;

    @Autowired
    private ProcessDueShipmentsUsecase processDueShipmentsUsecase;

    @Autowired
    private CancelShipmentUsecase cancelShipmentUsecase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private SimulateWarehouseOperationsUsecase simulateWarehouseOperations;

    @AfterEach
    void clearDatabase() {
        jdbcTemplate.update("DELETE FROM event_inbox");
        jdbcTemplate.update("DELETE FROM event_outbox");
        jdbcTemplate.update("DELETE FROM wms_dispatches");
        jdbcTemplate.update("DELETE FROM wms_pick_tasks");
        jdbcTemplate.update("DELETE FROM wms_picking_works");
        jdbcTemplate.update("DELETE FROM wms_shipment_lines");
        jdbcTemplate.update("DELETE FROM wms_wave_assignments");
        jdbcTemplate.update("DELETE FROM wms_waves");
        jdbcTemplate.update("DELETE FROM wms_shipments");
    }

    @Test
    void commitsInboxAndShipmentOnceForADuplicateDelivery() {
        UUID eventId = UUID.randomUUID();
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        var event = event(eventId, stockOperationId, orderId);

        emit(event);
        emit(event);

        assertThat(inboxCount(eventId)).isOne();
        assertThat(count("wms_shipments")).isOne();
        assertThat(count("wms_shipment_lines")).isOne();
        assertThat(shipmentStore.findByStockOperationId(stockOperationId)).hasValueSatisfying(shipment -> {
            assertThat(shipment.stockOperationId()).isEqualTo(stockOperationId);
            assertThat(shipment.orderId()).isEqualTo(orderId);
            assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);
            assertThat(shipment.releasePriority()).isEqualTo(80);
            assertThat(shipment.lines()).hasSize(1);
        });
    }

    @Test
    void keepsBusinessIdempotencyWhenTheSamePickingIsRepublishedWithANewEventId() {
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID firstEventId = UUID.randomUUID();
        UUID secondEventId = UUID.randomUUID();
        var first = event(firstEventId, stockOperationId, orderId);

        emit(first);
        emit(copyWithEventId(first, secondEventId));

        assertThat(inboxCount(firstEventId)).isOne();
        assertThat(inboxCount(secondEventId)).isOne();
        assertThat(count("wms_shipments")).isOne();
        assertThat(shipmentStore.findByOrderId(orderId)).hasSize(1);
    }

    @Test
    void recoversADueShipmentFromPersistenceAndCompletesTheSimulatedWarehouseFlowOnce() {
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        emit(event(UUID.randomUUID(), stockOperationId, orderId));
        UUID shipmentId = shipmentStore
                .findByStockOperationId(stockOperationId)
                .orElseThrow()
                .id();
        processDueShipmentsUsecase.execute();
        processDueShipmentsUsecase.execute();

        assertThat(shipmentStore.findById(shipmentId)).hasValueSatisfying(shipment -> {
            assertThat(shipment.status()).isEqualTo(ShipmentStatus.HANDED_OVER_TO_CARRIER);
            assertThat(pickingWorkStore.findByShipmentId(shipment.id()))
                    .hasValueSatisfying(work -> assertThat(work.pickTasks())
                            .isNotEmpty()
                            .allMatch(task -> task.status() == PickTaskStatus.PICKED));
        });
        assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM event_outbox WHERE aggregateid = ?",
                        Integer.class,
                        shipmentId.toString()))
                .isOne();
    }

    @Test
    void commitsCompletedCancellationMetadataAndItsCanonicalOutboxEventOnce() {
        UUID stockOperationId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID requestId = UUID.randomUUID();
        Instant requestedAt = Instant.parse("2026-08-12T09:00:00Z");
        String reason = "Customer changed mind";
        emit(event(UUID.randomUUID(), stockOperationId, orderId));
        UUID shipmentId = shipmentStore
                .findByStockOperationId(stockOperationId)
                .orElseThrow()
                .id();

        CancelShipmentStatus first =
                cancelShipmentUsecase.handle(new CancelShipmentCommand(requestId, shipmentId, requestedAt, reason));
        CancelShipmentStatus retry =
                cancelShipmentUsecase.handle(new CancelShipmentCommand(requestId, shipmentId, requestedAt, reason));

        assertThat(first).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(retry).isEqualTo(CancelShipmentStatus.ALREADY_ACCEPTED);
        assertThat(shipmentStore.findById(shipmentId)).hasValueSatisfying(shipment -> {
            assertThat(shipment.status()).isEqualTo(ShipmentStatus.CANCELLED);
            assertThat(shipment.cancellationStateValue()).contains(ShipmentCancellationState.COMPLETED);
            assertThat(shipment.cancellationRequestId()).isEqualTo(requestId);
            assertThat(shipment.cancellationRequestedAt()).isEqualTo(requestedAt);
            assertThat(shipment.cancellationReason()).isEqualTo(reason);
            assertThat(shipment.cancelledAt()).isAfter(requestedAt);
        });
        assertThat(jdbcTemplate.queryForMap("""
                        SELECT type, route, partition_key,
                               payload ->> 'shipmentId' AS shipment_id,
                               payload ->> 'stockOperationId' AS stock_operation_id,
                               payload ->> 'orderId' AS order_id,
                               payload ->> 'cancellationRequestId' AS cancellation_request_id,
                               payload ->> 'cancellationRequestedAt' AS cancellation_requested_at,
                               payload ->> 'cancellationReason' AS cancellation_reason,
                               payload ->> 'cancelledAt' AS cancelled_at
                        FROM event_outbox
                        WHERE aggregateid = ?
                        """, shipmentId.toString()))
                .containsEntry("type", ShipmentCancelledIntegrationEvent.EVENT_TYPE)
                .containsEntry("route", FulfillmentEventDestinations.SHIPMENT_EVENTS)
                .containsEntry("partition_key", orderId.toString())
                .containsEntry("shipment_id", shipmentId.toString())
                .containsEntry("stock_operation_id", stockOperationId.toString())
                .containsEntry("order_id", orderId.toString())
                .containsEntry("cancellation_request_id", requestId.toString())
                .containsEntry("cancellation_requested_at", requestedAt.toString())
                .containsEntry("cancellation_reason", reason)
                .containsKey("cancelled_at");
    }

    @Test
    void rollsBackCancellationAndOutboxTogether() {
        UUID shipmentId = createShipment();
        var command = cancellationCommand(shipmentId);
        var transaction = new TransactionTemplate(transactionManager);

        transaction.executeWithoutResult(status -> {
            assertThat(cancelShipmentUsecase.handle(command)).isEqualTo(CancelShipmentStatus.ACCEPTED);
            assertThat(cancellationEventCount(shipmentId)).isOne();
            status.setRollbackOnly();
        });

        assertThat(shipmentStore.findById(shipmentId)).hasValueSatisfying(shipment -> {
            assertThat(shipment.status()).isEqualTo(ShipmentStatus.CREATED);
            assertThat(shipment.cancellationStateValue()).isEmpty();
        });
        assertThat(cancellationEventCount(shipmentId)).isZero();
        assertThat(cancelShipmentUsecase.handle(command)).isEqualTo(CancelShipmentStatus.ACCEPTED);
        assertThat(cancelShipmentUsecase.handle(command)).isEqualTo(CancelShipmentStatus.ALREADY_ACCEPTED);
        assertThat(cancellationEventCount(shipmentId)).isOne();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void concurrentCancellationAndHandoverCommitOnlyOneOutcome(boolean cancellationWins) throws Exception {
        UUID shipmentId = createShipment();
        var cancellation = cancellationCommand(shipmentId);
        var handover = new SimulateWarehouseOperationsCommand(shipmentId, PostgreSQLTestConfiguration.NOW);
        var staleRead = new CountDownLatch(1);
        var winnerCommitted = new CountDownLatch(1);

        try (var executor = Executors.newSingleThreadExecutor()) {
            var loser = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                // Hold the original managed entity while the competing transaction commits a newer version.
                assertThat(shipmentStore.findById(shipmentId)).isPresent();
                staleRead.countDown();
                try {
                    assertThat(winnerCommitted.await(10, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                if (cancellationWins) {
                    simulateWarehouseOperations.handle(handover);
                } else {
                    cancelShipmentUsecase.handle(cancellation);
                }
            }));
            try {
                assertThat(staleRead.await(10, TimeUnit.SECONDS)).isTrue();
                if (cancellationWins) {
                    assertThat(cancelShipmentUsecase.handle(cancellation)).isEqualTo(CancelShipmentStatus.ACCEPTED);
                } else {
                    assertThat(simulateWarehouseOperations.handle(handover)).isTrue();
                }
            } finally {
                winnerCommitted.countDown();
            }
            if (cancellationWins) {
                assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(OptimisticLockingFailureException.class);
            } else {
                // Dispatch already reflects handover and rejects cancellation before the stale Shipment is flushed.
                assertThatThrownBy(() -> loser.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(IllegalStateException.class)
                        .hasRootCauseMessage("A handed-over ShipmentDispatch cannot be cancelled");
            }
        }

        assertThat(shipmentStore.findById(shipmentId))
                .hasValueSatisfying(shipment -> assertThat(shipment.status())
                        .isEqualTo(
                                cancellationWins ? ShipmentStatus.CANCELLED : ShipmentStatus.HANDED_OVER_TO_CARRIER));
        assertThat(cancelShipmentUsecase.handle(cancellation))
                .isEqualTo(cancellationWins ? CancelShipmentStatus.ALREADY_ACCEPTED : CancelShipmentStatus.REJECTED);
        assertThat(jdbcTemplate.queryForList(
                        "SELECT type FROM event_outbox WHERE aggregateid = ?", String.class, shipmentId.toString()))
                .containsExactly(
                        cancellationWins
                                ? ShipmentCancelledIntegrationEvent.EVENT_TYPE
                                : ShipmentHandedOverIntegrationEvent.EVENT_TYPE);
    }

    private UUID createShipment() {
        UUID stockOperationId = UUID.randomUUID();
        emit(event(UUID.randomUUID(), stockOperationId, UUID.randomUUID()));
        return shipmentStore
                .findByStockOperationId(stockOperationId)
                .orElseThrow()
                .id();
    }

    private CancelShipmentCommand cancellationCommand(UUID shipmentId) {
        return new CancelShipmentCommand(
                UUID.randomUUID(), shipmentId, PostgreSQLTestConfiguration.NOW, "Customer changed mind");
    }

    private int cancellationEventCount(UUID shipmentId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM event_outbox WHERE aggregateid = ? AND type = ?",
                Integer.class,
                shipmentId.toString(),
                ShipmentCancelledIntegrationEvent.EVENT_TYPE);
    }

    private void emit(OrderAllocationCommittedIntegrationEvent event) {
        transport.emit(
                WmsEventSubscriptions.FULFILLMENT_HANDOFF,
                AllocationEventDestinations.ALLOCATION_EVENTS,
                message(event),
                1);
    }

    private Message message(OrderAllocationCommittedIntegrationEvent event) {
        return MessageBuilder.withPayload(serializer.serialize(event))
                .withId(event.getEventId())
                .withType(event.eventType())
                .withPartitionId(event.getOrderId().toString())
                .withMessageDate(event.getAssignedAt())
                .withHeader(EventMessageHeaders.EVENT_TYPE, event.eventType())
                .withHeader(EventMessageHeaders.EVENT_AGGREGATE_TYPE, "Order")
                .withHeader(
                        EventMessageHeaders.EVENT_AGGREGATE_ID,
                        event.getOrderId().toString())
                .withHeader(EventMessageHeaders.EVENT_CONTRACT_VERSION, "1")
                .build();
    }

    private OrderAllocationCommittedIntegrationEvent event(UUID eventId, UUID stockOperationId, UUID orderId) {
        UUID sourceLocationId = UUID.randomUUID();
        return new OrderAllocationCommittedIntegrationEvent(
                eventId,
                stockOperationId,
                orderId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sourceLocationId,
                UUID.randomUUID(),
                List.of(new OrderAllocationCommittedIntegrationEvent.AssignedMove(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        "SKU-1",
                        3,
                        List.of(new OrderAllocationCommittedIntegrationEvent.BatchPick(UUID.randomUUID(), 3)))),
                Instant.parse("2026-08-12T08:00:00Z"),
                80,
                Instant.parse("2026-08-11T01:00:00Z"));
    }

    private OrderAllocationCommittedIntegrationEvent copyWithEventId(
            OrderAllocationCommittedIntegrationEvent event, UUID eventId) {
        return new OrderAllocationCommittedIntegrationEvent(
                eventId,
                event.getStockOperationId(),
                event.getOrderId(),
                event.getOwnerId(),
                event.getFacilityId(),
                event.getStockOperationTypeId(),
                event.getSourceLocationId(),
                event.getDestinationLocationId(),
                event.getMoves(),
                event.getDispatchBy(),
                event.getReleasePriority(),
                event.getAssignedAt());
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
