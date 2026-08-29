package com.flowzati.archone.inventory.movement.visibility.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.StockOperationView;
import com.flowzati.archone.inventory.movement.application.repo.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.application.service.StockOperationQueryService;
import com.flowzati.archone.inventory.movement.domain.StockOperationSource;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StockOperationQueryServiceTest {

    private final StockOperationViewStore stockOperationViewStore = mock(StockOperationViewStore.class);
    private final StockOperationQueryService queryService = new StockOperationQueryService(stockOperationViewStore);

    @Test
    void resolvesThePrimaryOrderThroughTheBoundedProjection() {
        UUID orderId = UUID.randomUUID();
        StockOperationSource source = StockOperationSource.primaryOrder(orderId.toString());
        StockOperationView view = mock(StockOperationView.class);
        when(stockOperationViewStore.findBySource(source)).thenReturn(Optional.of(view));

        assertThat(queryService.findPrimaryOrder(orderId)).containsSame(view);

        verify(stockOperationViewStore).findBySource(source);
    }
}
