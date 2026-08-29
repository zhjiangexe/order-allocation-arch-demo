package com.flowzati.archone.inventory.position.visibility.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flowzati.archone.inventory.position.application.StockQuantView;
import com.flowzati.archone.inventory.position.application.store.StockQuantViewStore;
import com.flowzati.archone.inventory.position.application.usecase.GetStockQuantUsecase;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Get stock quant view")
class GetStockQuantUsecaseTest {

    private static final UUID OWNER_ID = new UUID(0, 1);
    private static final UUID LOCATION_ID = new UUID(0, 2);

    @Test
    @DisplayName("returns an immutable snapshot even when an adapter supplies a mutable list")
    void protectsTheApplicationBoundary() {
        StockQuantViewStore stockQuantViewStore = mock(StockQuantViewStore.class);
        List<StockQuantView> adapterRows = new ArrayList<>(List.of(view()));
        when(stockQuantViewStore.findBatchesInLocation(OWNER_ID, LOCATION_ID)).thenReturn(adapterRows);

        List<StockQuantView> result =
                new GetStockQuantUsecase(stockQuantViewStore).getBatchesInLocation(OWNER_ID, LOCATION_ID);
        adapterRows.clear();

        assertThat(result).hasSize(1);
        assertThatThrownBy(() -> result.add(view())).isInstanceOf(UnsupportedOperationException.class);
    }

    private static StockQuantView view() {
        return new StockQuantView(
                new UUID(0, 3), "SKU-1", LocalDate.parse("2026-08-01"), LocalDate.parse("2026-12-31"), 10, 2);
    }
}
