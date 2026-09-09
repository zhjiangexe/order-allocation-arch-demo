package com.flowzati.archone.demo.orderfulfillment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.flowzati.archone.demo.orderfulfillment.rest.OrderFulfillmentDemoRest;
import com.flowzati.archone.demo.orderfulfillment.result.OrderFulfillmentView;
import com.flowzati.archone.demo.orderfulfillment.result.OrderView;
import com.flowzati.archone.demo.orderfulfillment.result.WorkflowQueryStatus;
import com.flowzati.archone.demo.orderfulfillment.service.OrderFulfillmentQueryService;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import com.flowzati.archone.inventory.movement.entrypoint.rest.StockOperationResponse;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentAllocationState;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentCancellationState;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentOutcome;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentPhase;
import com.flowzati.archone.orchestration.contract.workflow.order.result.OrderFulfillmentSnapshot;
import com.flowzati.archone.orchestration.contract.workflow.order.result.ShipmentTerminalStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(OrderFulfillmentDemoRest.class)
class OrderFulfillmentDemoRestTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private OrderFulfillmentQueryService queryService;

    @Test
    @DisplayName("履約查詢應以一份 response 組合 source、stock operation 與 WMS")
    void shouldReturnComposedFulfillmentView() {
        when(queryService.query(ORDER_ID)).thenReturn(view());

        MvcTestResultAssert response = assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$.orchestrationMode").isEqualTo("events");
        response.bodyJson().extractingPath("$.workflowQueryStatus").isEqualTo("NOT_APPLICABLE");
        response.bodyJson().extractingPath("$.temporalWorkflow").isNull();
        response.bodyJson().extractingPath("$.order.orderId").isEqualTo(ORDER_ID.toString());
        response.bodyJson().extractingPath("$.order.externalOrderNo").isEqualTo("DEMO-001");
        response.bodyJson().extractingPath("$.stockOperation.source.type").isEqualTo("ORDER");
        response.bodyJson()
                .extractingPath("$.stockOperation.moves[0].batches[0].quantity")
                .isEqualTo(5);
        response.bodyJson().doesNotHavePath("$.stockOperation.moves[0].moveLines");
        response.bodyJson().extractingPath("$.shipments.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("不存在的訂單應回 404")
    void shouldReturnNotFoundForUnknownOrder() {
        when(queryService.query(ORDER_ID)).thenThrow(new NoSuchElementException("Order not found: " + ORDER_ID));

        assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID))
                .hasStatus(404);
    }

    @ParameterizedTest
    @EnumSource(
            value = WorkflowQueryStatus.class,
            names = {"AVAILABLE", "NOT_FOUND", "UNAVAILABLE"})
    void temporalQueryAvailabilityDoesNotRemoveBusinessFields(WorkflowQueryStatus status) {
        OrderFulfillmentView base = view();
        OrderFulfillmentSnapshot snapshot = status == WorkflowQueryStatus.AVAILABLE
                ? new OrderFulfillmentSnapshot(
                        ORDER_ID,
                        OrderFulfillmentPhase.FINISHED,
                        OrderFulfillmentAllocationState.COMMITTED,
                        OrderFulfillmentCancellationState.NONE,
                        null,
                        null,
                        OrderFulfillmentOutcome.FULFILLMENT_COMPLETED,
                        base.stockOperation().operation().stockOperationId(),
                        UUID.randomUUID(),
                        ShipmentTerminalStatus.HANDED_OVER,
                        Instant.parse("2026-08-20T09:00:00Z"),
                        null,
                        Instant.parse("2026-08-20T09:00:01Z"))
                : null;
        when(queryService.query(ORDER_ID))
                .thenReturn(new OrderFulfillmentView(
                        base.order(), base.stockOperation(), base.shipments(), snapshot, "temporal", status));
        MvcTestResultAssert response = assertThat(mvc.get().uri("/demo/orders/{orderId}/fulfillment", ORDER_ID));
        response.hasStatus(200);
        response.bodyJson().extractingPath("$.orchestrationMode").isEqualTo("temporal");
        response.bodyJson().extractingPath("$.workflowQueryStatus").isEqualTo(status.name());
        response.bodyJson().extractingPath("$.order.orderId").isEqualTo(ORDER_ID.toString());
        response.bodyJson()
                .extractingPath("$.stockOperation.moves[0].batches[0].quantity")
                .isEqualTo(5);
        response.bodyJson().extractingPath("$.shipments.length()").isEqualTo(0);
        if (snapshot == null) {
            response.bodyJson().extractingPath("$.temporalWorkflow").isNull();
        } else {
            response.bodyJson().extractingPath("$.temporalWorkflow.phase").isEqualTo("FINISHED");
            response.bodyJson().extractingPath("$.temporalWorkflow.updatedAt").isEqualTo("2026-08-20T09:00:01Z");
            response.bodyJson().doesNotHavePath("$.temporalWorkflow.phaseEnteredAt");
        }
    }

    private static OrderFulfillmentView view() {
        OrderView order = new OrderView(
                ORDER_ID,
                UUID.fromString("00000000-0000-7000-8000-000000000002"),
                "DEMO-001",
                UUID.fromString("00000000-0000-7000-8000-000000000003"),
                "TW-N",
                "Taipei",
                LocalDate.parse("2026-08-21"),
                Instant.parse("2026-08-20T16:00:00Z"),
                50,
                "PENDING",
                Instant.parse("2026-08-20T08:00:00Z"),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                List.of());
        Instant enqueuedAt = Instant.parse("2026-08-20T08:00:00Z");
        StockOperationResponse stockOperation = new StockOperationResponse(
                new StockOperationResponse.SourceTrace(MovementSourceType.ORDER, ORDER_ID.toString(), "PRIMARY"),
                new StockOperationResponse.Operation(
                        UUID.fromString("00000000-0000-7000-8000-000000000010"),
                        UUID.fromString("00000000-0000-7000-8000-000000000011"),
                        StockOperationDirection.OUTBOUND,
                        UUID.fromString("00000000-0000-7000-8000-000000000002"),
                        UUID.fromString("00000000-0000-7000-8000-000000000012"),
                        UUID.fromString("00000000-0000-7000-8000-000000000013"),
                        MovementAssignmentPolicy.SHIP_COMPLETE,
                        enqueuedAt,
                        enqueuedAt.plusSeconds(3600),
                        50,
                        StockOperationState.ASSIGNED),
                List.of(new StockOperationResponse.Move(
                        UUID.fromString("00000000-0000-7000-8000-000000000014"),
                        UUID.fromString("00000000-0000-7000-8000-000000000015").toString(),
                        1,
                        "E2E-TEA",
                        5,
                        MoveState.ASSIGNED,
                        enqueuedAt,
                        enqueuedAt.plusSeconds(60),
                        List.of(new StockOperationResponse.Batch(
                                UUID.fromString("00000000-0000-7000-8000-000000000016"),
                                UUID.fromString("00000000-0000-7000-8000-000000000012"),
                                "E2E-TEA",
                                LocalDate.parse("2026-08-01"),
                                LocalDate.parse("2026-09-01"),
                                5)))));
        return new OrderFulfillmentView(
                order, stockOperation, List.of(), null, "events", WorkflowQueryStatus.NOT_APPLICABLE);
    }
}
