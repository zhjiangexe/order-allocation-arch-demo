package com.flowzati.archone.messaging.producer.jdbc;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.api.MessageHeaders;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Maps physical columns from standard headers and optionally maps aggregate columns from a
 * protocol-owned pair of header names. The producer JDBC module therefore remains event-neutral.
 */
public final class HeaderMappedOutboxMessageMapper implements OutboxMessageMapper {

    private final Optional<AggregateHeaderNames> aggregateHeaders;

    private HeaderMappedOutboxMessageMapper(Optional<AggregateHeaderNames> aggregateHeaders) {
        this.aggregateHeaders = aggregateHeaders;
    }

    public static HeaderMappedOutboxMessageMapper withoutAggregateHeaders() {
        return new HeaderMappedOutboxMessageMapper(Optional.empty());
    }

    public static HeaderMappedOutboxMessageMapper withAggregateHeaders(
            String aggregateTypeHeader, String aggregateIdHeader) {
        MessageHeaders.validateName(aggregateTypeHeader);
        MessageHeaders.validateName(aggregateIdHeader);
        if (aggregateTypeHeader.equals(aggregateIdHeader)) {
            throw new IllegalArgumentException("Aggregate header names must be different");
        }
        return new HeaderMappedOutboxMessageMapper(
                Optional.of(new AggregateHeaderNames(aggregateTypeHeader, aggregateIdHeader)));
    }

    @Override
    public OutboxMessage map(String destination, Message message) {
        if (destination == null || destination.isBlank() || message == null) {
            throw new IllegalArgumentException("Outbox destination and message are required");
        }
        String normalizedDestination = message.requiredHeader(MessageHeaders.DESTINATION);
        if (!destination.equals(normalizedDestination)) {
            throw new IllegalArgumentException("Outbox destination does not match message header");
        }

        Optional<String> aggregateType = aggregateHeaders.map(names -> message.requiredHeader(names.type()));
        Optional<String> aggregateId = aggregateHeaders.map(names -> message.requiredHeader(names.id()));

        Map<String, String> persistedHeaders = new LinkedHashMap<>(message.headers());
        OutboxPhysicalHeaders.ALL.forEach(persistedHeaders::remove);

        return new OutboxMessage(
                message.id(),
                aggregateType,
                aggregateId,
                message.type(),
                destination,
                message.partitionId(),
                message.payload(),
                message.messageDate(),
                persistedHeaders);
    }

    private record AggregateHeaderNames(String type, String id) {}
}
