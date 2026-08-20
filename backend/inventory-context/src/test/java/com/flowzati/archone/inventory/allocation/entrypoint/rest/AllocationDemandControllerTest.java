package com.flowzati.archone.inventory.allocation.entrypoint.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandQueryService;
import com.flowzati.archone.inventory.allocation.application.query.AllocationDemandView;
import com.flowzati.archone.inventory.allocation.application.query.AllocationWaitingReason;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationDemandStatus;
import com.flowzati.archone.inventory.allocation.domain.type.AllocationSourceType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

@WebMvcTest(AllocationDemandController.class)
class AllocationDemandControllerTest {

    private static final UUID DEMAND_ID = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Autowired
    private MockMvcTester mvc;

    @MockitoBean
    private AllocationDemandQueryService queryService;

    @Test
    @DisplayName("pending demand 查詢應回傳即時缺貨原因與缺少數量")
    void shouldExplainPendingDemand() {
        when(queryService.listPending(20)).thenReturn(List.of(pendingDemand()));

        MvcTestResultAssert response = assertThat(mvc.get().uri("/allocation-demands?status=PENDING&limit=20"));

        response.hasStatus(200);
        response.bodyJson().extractingPath("$[0].allocationDemandId").isEqualTo(DEMAND_ID.toString());
        response.bodyJson().extractingPath("$[0].status").isEqualTo("PENDING");
        response.bodyJson().extractingPath("$[0].waitingReason").isEqualTo("NO_ALLOCATABLE_STOCK");
        response.bodyJson().extractingPath("$[0].requiredQuantities.SKU-1").isEqualTo(5);
        response.bodyJson().extractingPath("$[0].missingQuantities.SKU-1").isEqualTo(5);
        verify(queryService).listPending(20);
    }

    @Test
    @DisplayName("目前只開放 pending queue，不接受模糊的其他狀態")
    void shouldRejectUnsupportedStatus() {
        assertThat(mvc.get().uri("/allocation-demands?status=ALLOCATED")).hasStatus(400);

        verifyNoInteractions(queryService);
    }

    @Test
    @DisplayName("limit 應限制在操作台可控範圍")
    void shouldRejectAnExcessiveLimit() {
        assertThat(mvc.get().uri("/allocation-demands?status=PENDING&limit=201"))
                .hasStatus(400);

        verifyNoInteractions(queryService);
    }

    private static AllocationDemandView pendingDemand() {
        return new AllocationDemandView(
                DEMAND_ID,
                AllocationSourceType.ORDER,
                "00000000-0000-7000-8000-000000000002",
                "PRIMARY",
                UUID.fromString("00000000-0000-7000-8000-000000000003"),
                UUID.fromString("00000000-0000-7000-8000-000000000004"),
                UUID.fromString("00000000-0000-7000-8000-000000000005"),
                Instant.parse("2026-08-21T08:00:00Z"),
                50,
                Instant.parse("2026-08-20T08:00:00Z"),
                AllocationDemandStatus.PENDING,
                AllocationWaitingReason.NO_ALLOCATABLE_STOCK,
                null,
                Map.of("SKU-1", 5),
                Map.of("SKU-1", 0),
                Map.of("SKU-1", 5),
                List.of(),
                List.of(),
                List.of());
    }
}
