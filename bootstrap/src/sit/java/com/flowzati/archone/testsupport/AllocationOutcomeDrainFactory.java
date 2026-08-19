package com.flowzati.archone.testsupport;

import com.flowzati.archone.contracts.promising.v1.AllocationChannels;
import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.ordering.application.event.OrderingEventSubscriptions;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds an in-memory transport around the same typed dispatcher and decorators used at runtime. */
@Component
public class AllocationOutcomeDrainFactory {

    private final JdbcTemplate jdbcTemplate;
    private final KafkaMessageMapper messageMapper;
    private final ControllableMessageConsumerImplementation transport;
    private final String physicalDestination;

    public AllocationOutcomeDrainFactory(
            JdbcTemplate jdbcTemplate,
            KafkaMessageMapper messageMapper,
            ChannelMapping channelMapping,
            ControllableMessageConsumerImplementation transport) {
        this.jdbcTemplate = jdbcTemplate;
        this.messageMapper = messageMapper;
        this.transport = transport;
        this.physicalDestination = channelMapping.transform(AllocationChannels.ALLOCATION_EVENTS);
    }

    public AllocationOutcomeDrain create() {
        return new AllocationOutcomeDrain(
                jdbcTemplate,
                messageMapper,
                (ignoredDestination, message) ->
                        transport.emit(OrderingEventSubscriptions.ALLOCATION_RESULTS, physicalDestination, message, 1));
    }
}
