package com.flowzati.archone.testsupport;

import com.flowzati.archone.messaging.api.ChannelMapping;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.messaging.testsupport.ControllableMessageConsumerImplementation;
import com.flowzati.archone.inventory.allocation.application.event.AllocationEventSubscriptions;
import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Builds an in-memory transport around the production inventory-availability typed chain. */
@Component
public class InventoryEventDrainFactory {

  private final JdbcTemplate jdbcTemplate;
  private final KafkaMessageMapper messageMapper;
  private final ControllableMessageConsumerImplementation transport;
  private final String physicalDestination;

  public InventoryEventDrainFactory(
      JdbcTemplate jdbcTemplate,
      KafkaMessageMapper messageMapper,
      ChannelMapping channelMapping,
      ControllableMessageConsumerImplementation transport
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.messageMapper = messageMapper;
    this.transport = transport;
    this.physicalDestination = channelMapping.transform(InventoryChannels.STOCK_EVENTS);
  }

  public InventoryEventDrain create() {
    return new InventoryEventDrain(
        jdbcTemplate,
        messageMapper,
        (ignoredDestination, message) -> transport.emit(
            AllocationEventSubscriptions.INVENTORY_AVAILABILITY,
            physicalDestination,
            message,
            1));
  }
}
