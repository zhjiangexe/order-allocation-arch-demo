package com.flowzati.archone.messaging.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelMappingTest {

    @Test
    void identityMappingPreservesTheLogicalChannel() {
        assertThat(IdentityChannelMapping.INSTANCE.transform("ordering.order-events"))
                .isEqualTo("ordering.order-events");
    }

    @Test
    void mapBasedMappingIsImmutableAndFallsBackToIdentity() {
        LinkedHashMap<String, String> source =
                new LinkedHashMap<>(Map.of("order-events", "prod.ordering.order-events"));
        ChannelMapping mapping = new MapBasedChannelMapping(source);
        source.put("order-events", "changed-after-construction");

        assertThat(mapping.transform("order-events")).isEqualTo("prod.ordering.order-events");
        assertThat(mapping.transform("stock-events")).isEqualTo("stock-events");
    }

    @Test
    void rejectsAmbiguousReverseMappings() {
        assertThatThrownBy(() -> new MapBasedChannelMapping(Map.of(
                        "order-events", "shared-events",
                        "stock-events", "shared-events")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multiple logical channels map to the same destination")
                .hasMessageContaining("shared-events");
    }
}
