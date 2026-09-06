package com.flowzati.archone.inventory.movement.visibility.entrypoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.result.StockMoveLineView;
import com.flowzati.archone.inventory.movement.application.result.StockMoveView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationHeaderView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationSourceView;
import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.inventory.movement.domain.policy.MovementAssignmentPolicy;
import com.flowzati.archone.inventory.movement.domain.valueobject.MoveState;
import com.flowzati.archone.inventory.movement.domain.valueobject.MovementSourceType;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationDirection;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationState;
import com.flowzati.archone.inventory.movement.entrypoint.rest.StockOperationRest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

@WebMvcTest(StockOperationRest.class)
class StockOperationRestTest {

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private StockOperationQueryService queryService;

    @Test
    void returnsCanonicalPickingQueue() {
        UUID stockOperationId = UUID.randomUUID();
        when(queryService.listConfirmed(20)).thenReturn(List.of(operation(stockOperationId)));

        var response = assertThat(mvc.get().uri("/stock-operations?state=CONFIRMED&limit=20"));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$[0].operation.stockOperationId").isEqualTo(stockOperationId.toString());
        response.bodyJson().extractingPath("$[0].source.type").isEqualTo("ORDER");
        response.bodyJson().extractingPath("$[0].moves[0].sourceLineId").isEqualTo("line-1");
        response.bodyJson().extractingPath("$[0].moves[0].batches[0].quantity").isEqualTo(5);
        response.bodyJson().doesNotHavePath("$[0].moves[0].moveLines");
        verify(queryService).listConfirmed(20);
    }

    @Test
    void rejectsUnsupportedState() {
        assertThat(mvc.get().uri("/stock-operations?state=ASSIGNED")).hasStatus(400);

        verifyNoInteractions(queryService);
    }

    private static StockOperationView operation(UUID stockOperationId) {
        Instant now = Instant.parse("2026-08-20T08:00:00Z");
        return new StockOperationView(
                new StockOperationSourceView(
                        MovementSourceType.ORDER, UUID.randomUUID().toString(), "PRIMARY"),
                new StockOperationHeaderView(
                        stockOperationId,
                        UUID.randomUUID(),
                        StockOperationDirection.OUTBOUND,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        MovementAssignmentPolicy.SHIP_COMPLETE,
                        now,
                        now.plusSeconds(3600),
                        50,
                        StockOperationState.CONFIRMED),
                List.of(new StockMoveView(
                        UUID.randomUUID(),
                        "line-1",
                        1,
                        "SKU-1",
                        5,
                        MoveState.CONFIRMED,
                        now,
                        null,
                        List.of(new StockMoveLineView(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                "SKU-1",
                                java.time.LocalDate.parse("2026-08-01"),
                                java.time.LocalDate.parse("2026-09-01"),
                                5)))));
    }
}
