package com.flowzati.archone.inventory.movement.visibility.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.movement.application.result.StockOperationView;
import com.flowzati.archone.inventory.movement.application.store.StockOperationViewStore;
import com.flowzati.archone.inventory.movement.application.usecase.GetPrimaryOrderStockOperationUsecase;
import com.flowzati.archone.inventory.movement.domain.valueobject.StockOperationSource;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetPrimaryOrderStockOperationUsecaseTest {

    private final StockOperationViewStore stockOperationViewStore = mock(StockOperationViewStore.class);
    private final GetPrimaryOrderStockOperationUsecase usecase =
            new GetPrimaryOrderStockOperationUsecase(stockOperationViewStore);

    @Test
    void resolvesThePrimaryOrderThroughTheBoundedProjection() {
        UUID orderId = UUID.randomUUID();
        StockOperationSource source = StockOperationSource.primaryOrder(orderId.toString());
        StockOperationView view = mock(StockOperationView.class);
        when(stockOperationViewStore.findBySource(source)).thenReturn(Optional.of(view));

        assertThat(usecase.get(orderId)).containsSame(view);

        verify(stockOperationViewStore).findBySource(source);
    }
}
