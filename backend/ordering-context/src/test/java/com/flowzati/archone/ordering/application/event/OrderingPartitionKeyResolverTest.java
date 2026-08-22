package com.flowzati.archone.ordering.application.event;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderingPartitionKeyResolverTest {

    private static final UUID ORDER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FACILITY_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Test
    void usesOrderIdByDefault() {
        assertThat(new OrderingPartitionKeyResolver("order-id").resolve(ORDER_ID, OWNER_ID, FACILITY_ID))
                .isEqualTo(ORDER_ID.toString());
    }

    @Test
    void usesOwnerAndFacilityForTheStockStrategy() {
        assertThat(new OrderingPartitionKeyResolver("stock").resolve(ORDER_ID, OWNER_ID, FACILITY_ID))
                .isEqualTo(OWNER_ID + "/" + FACILITY_ID);
    }
}
