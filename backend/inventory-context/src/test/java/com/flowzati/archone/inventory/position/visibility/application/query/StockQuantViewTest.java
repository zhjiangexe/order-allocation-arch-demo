package com.flowzati.archone.inventory.position.visibility.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.inventory.position.application.StockQuantView;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Stock quant read projection")
class StockQuantViewTest {

    private static final LocalDate EXPIRY_DATE = LocalDate.parse("2026-08-29");

    @Test
    @DisplayName("is immutable by construction and exposes no stock mutation operations")
    void isAnImmutableReadModel() {
        assertThat(StockQuantView.class.isRecord()).isTrue();
        assertThat(Arrays.stream(StockQuantView.class.getMethods()).map(method -> method.getName()))
                .doesNotContain("reserve", "release", "receive", "consume");
    }

    @Test
    @DisplayName("derives available-to-promise and expiry without mutating quantities")
    void derivesReadOnlyFacts() {
        StockQuantView view = view(10, 4);

        assertThat(view.availableToPromise()).isEqualTo(6);
        assertThat(view.isExpired(EXPIRY_DATE)).isFalse();
        assertThat(view.isExpired(EXPIRY_DATE.plusDays(1))).isTrue();
        assertThat(view.onHandQuantity()).isEqualTo(10);
        assertThat(view.reservedQuantity()).isEqualTo(4);
    }

    @Test
    @DisplayName("rejects invalid projected identities and quantity invariants")
    void validatesProjectedRows() {
        assertThatThrownBy(() -> new StockQuantView(null, "SKU-1", EXPIRY_DATE.minusDays(1), EXPIRY_DATE, 10, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ID");
        assertThatThrownBy(() -> view(3, 4))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed");
    }

    private static StockQuantView view(int onHandQuantity, int reservedQuantity) {
        return new StockQuantView(
                new UUID(0, 1), "SKU-1", EXPIRY_DATE.minusMonths(1), EXPIRY_DATE, onHandQuantity, reservedQuantity);
    }
}
