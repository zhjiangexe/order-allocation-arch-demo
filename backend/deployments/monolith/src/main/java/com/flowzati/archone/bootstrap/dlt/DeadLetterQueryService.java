package com.flowzati.archone.bootstrap.dlt;

import com.flowzati.archone.contracts.cancel.v1.CancellationEventDestinations;
import com.flowzati.archone.contracts.fulfillment.v1.FulfillmentEventDestinations;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.contracts.ordering.v1.OrderingEventDestinations;
import com.flowzati.archone.contracts.promising.v1.AllocationEventDestinations;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeaders;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/** Bounded, read-only inspection of the demo application's dead-letter topics. */
@Service
@Profile("dev")
public class DeadLetterQueryService {

    public static final List<String> SOURCE_TOPICS = List.of(
            OrderingEventDestinations.ORDER_EVENTS,
            InventoryEventDestinations.STOCK_EVENTS,
            AllocationEventDestinations.ALLOCATION_EVENTS,
            FulfillmentEventDestinations.FULFILLMENT_HANDOFFS,
            CancellationEventDestinations.CANCELLATION_REQUESTS,
            CancellationEventDestinations.SHIPMENT_EVENTS,
            CancellationEventDestinations.ORDERING_CANCELLATION_REQUESTS,
            CancellationEventDestinations.ORDERING_CANCELLATION_RESULTS);

    private static final int MAX_RECORDS_PER_PARTITION = 200;
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(1);
    private static final Duration SCAN_TIMEOUT = Duration.ofSeconds(10);
    private static final Comparator<DeadLetterSummary> NEWEST_FIRST = Comparator.comparingLong(
                    DeadLetterSummary::timestampMillis)
            .thenComparing(DeadLetterSummary::topic)
            .thenComparingInt(DeadLetterSummary::partition)
            .thenComparingLong(DeadLetterSummary::offset)
            .reversed();

    private final String bootstrapServers;

    public DeadLetterQueryService(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    public DeadLetterPage list(String sourceTopic, String consumerGroup, int page, int limit) {
        if (page < 0 || page > 20 || limit < 1 || limit > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "page must be 0..20 and limit must be 1..100");
        }
        if (sourceTopic != null && !SOURCE_TOPICS.contains(sourceTopic)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown source topic");
        }
        List<String> topics = sourceTopic == null
                ? SOURCE_TOPICS.stream().map(DeadLetterQueryService::dltTopic).toList()
                : List.of(dltTopic(sourceTopic));
        try (KafkaConsumer<String, String> consumer = consumer()) {
            List<TopicPartition> partitions = new ArrayList<>();
            List<String> missingTopics = new ArrayList<>();
            var available = consumer.listTopics(POLL_TIMEOUT);
            for (String topic : topics) {
                var metadata = available.get(topic);
                if (metadata == null || metadata.isEmpty()) {
                    missingTopics.add(topic);
                } else {
                    metadata.forEach(partition -> partitions.add(new TopicPartition(topic, partition.partition())));
                }
            }
            if (partitions.isEmpty()) {
                return new DeadLetterPage(List.of(), page, limit, false, false, missingTopics, SOURCE_TOPICS);
            }
            consumer.assign(partitions);
            Map<TopicPartition, Long> beginnings = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> ends = consumer.endOffsets(partitions);
            boolean truncated = false;
            for (TopicPartition partition : partitions) {
                long start = Math.max(beginnings.get(partition), ends.get(partition) - MAX_RECORDS_PER_PARTITION);
                truncated |= start > beginnings.get(partition);
                consumer.seek(partition, start);
            }
            List<DeadLetterSummary> found = new ArrayList<>();
            long deadline = System.nanoTime() + SCAN_TIMEOUT.toNanos();
            while (!finished(consumer, ends)) {
                if (System.nanoTime() > deadline) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DLT scan timed out");
                }
                for (ConsumerRecord<String, String> record : consumer.poll(POLL_TIMEOUT)) {
                    if (record.offset() < ends.get(new TopicPartition(record.topic(), record.partition()))) {
                        DeadLetterSummary summary = summary(record);
                        if (consumerGroup == null || consumerGroup.equals(summary.consumerGroupId())) {
                            found.add(summary);
                        }
                    }
                }
            }
            found.sort(NEWEST_FIRST);
            int from = Math.min(page * limit, found.size());
            int to = Math.min(from + limit, found.size());
            return new DeadLetterPage(
                    found.subList(from, to), page, limit, to < found.size(), truncated, missingTopics, SOURCE_TOPICS);
        }
    }

    public DeadLetterDetail detail(String topic, int partition, long offset) {
        if (!SOURCE_TOPICS.stream()
                        .map(DeadLetterQueryService::dltTopic)
                        .collect(Collectors.toSet())
                        .contains(topic)
                || partition < 0
                || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid DLT record location");
        }
        try (KafkaConsumer<String, String> consumer = consumer()) {
            var metadata = consumer.listTopics(POLL_TIMEOUT).getOrDefault(topic, List.of());
            if (metadata.stream().noneMatch(candidate -> candidate.partition() == partition)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DLT partition not found");
            }
            TopicPartition location = new TopicPartition(topic, partition);
            consumer.assign(List.of(location));
            long beginning = consumer.beginningOffsets(List.of(location)).get(location);
            long end = consumer.endOffsets(List.of(location)).get(location);
            if (offset < beginning || offset >= end) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "DLT record not found");
            }
            consumer.seek(location, offset);
            long deadline = System.nanoTime() + SCAN_TIMEOUT.toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(POLL_TIMEOUT)) {
                    if (record.offset() == offset) {
                        return new DeadLetterDetail(summary(record), record.value());
                    }
                }
            }
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DLT lookup timed out");
        }
    }

    private KafkaConsumer<String, String> consumer() {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "archone-dlt-inspector",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG,
                false,
                ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG,
                false,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class));
    }

    private static boolean finished(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> ends) {
        return ends.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());
    }

    private static String dltTopic(String sourceTopic) {
        return sourceTopic + "-dlt";
    }

    private static DeadLetterSummary summary(ConsumerRecord<String, String> record) {
        return new DeadLetterSummary(
                record.topic(),
                record.partition(),
                record.offset(),
                record.timestamp(),
                record.timestamp() < 0
                        ? null
                        : Instant.ofEpochMilli(record.timestamp()).toString(),
                record.key(),
                originalTopic(record),
                header(record, KafkaDeadLetterHeaders.SUBSCRIBER_ID),
                header(record, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID),
                header(record, KafkaHeaders.DLT_EXCEPTION_FQCN),
                header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE));
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header value = record.headers().lastHeader(name);
        return value == null ? null : new String(value.value(), StandardCharsets.UTF_8);
    }

    private static String originalTopic(ConsumerRecord<String, String> record) {
        String physical = header(record, KafkaDeadLetterHeaders.ORIGINAL_PHYSICAL_DESTINATION);
        return physical == null ? header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC) : physical;
    }

    public record DeadLetterSummary(
            String topic,
            int partition,
            long offset,
            long timestampMillis,
            String timestamp,
            String key,
            String originalTopic,
            String subscriberId,
            String consumerGroupId,
            String exceptionClass,
            String exceptionMessage) {}

    public record DeadLetterPage(
            List<DeadLetterSummary> items,
            int page,
            int limit,
            boolean hasMore,
            boolean truncated,
            List<String> missingTopics,
            List<String> sourceTopics) {}

    public record DeadLetterDetail(DeadLetterSummary record, String payload) {}
}
