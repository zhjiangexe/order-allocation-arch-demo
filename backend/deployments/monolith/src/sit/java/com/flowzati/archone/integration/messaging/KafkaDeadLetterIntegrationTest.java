package com.flowzati.archone.integration.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowzati.archone.bootstrap.dlt.DeadLetterQueryService;
import com.flowzati.archone.contracts.inventory.v1.InventoryEventDestinations;
import com.flowzati.archone.messaging.consumer.common.MessageFailureCategory;
import com.flowzati.archone.messaging.consumer.common.MessageFailureClassification;
import com.flowzati.archone.messaging.consumer.common.ResolvedMessageSubscription;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterErrorHandlerFactory;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeaders;
import com.flowzati.archone.messaging.spring.consumer.kafka.KafkaDeadLetterHeadersProvider;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;
import org.springframework.kafka.listener.MessageListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.util.backoff.FixedBackOff;
import org.springframework.web.server.ResponseStatusException;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

class KafkaDeadLetterIntegrationTest {

    private static final String SOURCE = "ordering.order-events";
    private static final String DLT = SOURCE + "-dlt";

    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:4.1.2"));

    @BeforeAll
    static void startKafka() {
        KAFKA.start();
    }

    @AfterAll
    static void stopKafka() {
        KAFKA.stop();
    }

    @Test
    void realConsumerFailureFlowsFromSourceTopicToDlt() throws Exception {
        String sourceTopic = "test.consumer-failure";
        String dltTopic = sourceTopic + "-dlt";
        String bootstrapServers = KAFKA.getBootstrapServers();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            admin.createTopics(List.of(new NewTopic(sourceTopic, 1, (short) 1), new NewTopic(dltTopic, 1, (short) 1)))
                    .all()
                    .get();
        }

        var consumerProperties = Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-real-consumer",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class);
        var producerProperties = Map.<String, Object>of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProperties));
        @SuppressWarnings({"rawtypes", "unchecked"})
        KafkaOperations<Object, Object> operations = (KafkaOperations) template;
        var subscription = new ResolvedMessageSubscription(
                "dlt-test-subscriber", "dlt-real-consumer", Map.of(sourceTopic, "test-consumer-failure"));
        var errorHandler = KafkaDeadLetterErrorHandlerFactory.create(
                operations,
                new FixedBackOff(0, 0),
                failure -> MessageFailureClassification.retryable(MessageFailureCategory.HANDLER),
                KafkaDeadLetterHeadersProvider.forSubscription(subscription));
        var properties = new ContainerProperties(sourceTopic);
        properties.setGroupId(subscription.consumerGroupId());
        properties.setMessageListener((MessageListener<String, String>) record -> {
            throw new IllegalArgumentException("deliberate consumer failure");
        });
        var container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, String>(consumerProperties), properties);
        container.setCommonErrorHandler(errorHandler);

        try (KafkaConsumer<String, String> dltConsumer = new KafkaConsumer<>(consumerProperties)) {
            dltConsumer.subscribe(List.of(dltTopic));
            container.start();
            template.send(sourceTopic, "test-key", "{\"test\":true}").get();

            ConsumerRecord<String, String> deadLetter = null;
            for (int attempt = 0; attempt < 10 && deadLetter == null; attempt++) {
                var records = dltConsumer.poll(Duration.ofSeconds(1));
                if (!records.isEmpty()) {
                    deadLetter = records.iterator().next();
                }
            }
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.key()).isEqualTo("test-key");
            assertThat(deadLetter.value()).isEqualTo("{\"test\":true}");
            assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(sourceTopic);
            assertThat(header(deadLetter, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
                    .isEqualTo(subscription.consumerGroupId());
        } finally {
            container.stop();
            template.destroy();
        }
    }

    @Test
    void exhaustedFailureReachesMatchingDltWithSubscriptionIdentity() throws Exception {
        String bootstrapServers = KAFKA.getBootstrapServers();
        try (Admin admin = Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            List<NewTopic> topics = new ArrayList<>();
            topics.add(new NewTopic(SOURCE, 1, (short) 1));
            DeadLetterQueryService.SOURCE_TOPICS.forEach(
                    source -> topics.add(new NewTopic(source + "-dlt", 1, (short) 1)));
            admin.createTopics(topics).all().get();
        }

        Map<String, Object> producerProperties = Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        KafkaTemplate<String, String> template =
                new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(producerProperties));
        @SuppressWarnings({"rawtypes", "unchecked"})
        KafkaOperations<Object, Object> operations = (KafkaOperations) template;
        ResolvedMessageSubscription subscription = new ResolvedMessageSubscription(
                "allocation-inbox-scope", "allocation-kafka-group", Map.of(SOURCE, "ordering-events"));
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG,
                "dlt-integration-test",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
                "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
                StringDeserializer.class))) {
            consumer.subscribe(List.of(DLT));
            var handler = KafkaDeadLetterErrorHandlerFactory.create(
                    operations,
                    new FixedBackOff(0, 0),
                    failure -> MessageFailureClassification.retryable(MessageFailureCategory.HANDLER),
                    KafkaDeadLetterHeadersProvider.forSubscription(subscription));
            ConsumerRecord<String, String> source = new ConsumerRecord<>(SOURCE, 0, 42L, "order-1", "payload");

            assertThat(handler.handleOne(new IllegalArgumentException("invalid contract"), source, null, null))
                    .isTrue();

            ConsumerRecord<String, String> deadLetter = null;
            for (int attempt = 0; attempt < 10 && deadLetter == null; attempt++) {
                var records = consumer.poll(Duration.ofSeconds(1));
                if (!records.isEmpty()) {
                    deadLetter = records.iterator().next();
                }
            }
            assertThat(deadLetter).isNotNull();
            assertThat(deadLetter.topic()).isEqualTo(DLT);
            assertThat(deadLetter.partition()).isEqualTo(source.partition());
            assertThat(deadLetter.key()).isEqualTo(source.key());
            assertThat(deadLetter.value()).isEqualTo(source.value());
            assertThat(header(deadLetter, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(SOURCE);
            assertThat(header(deadLetter, KafkaDeadLetterHeaders.SUBSCRIBER_ID)).isEqualTo(subscription.subscriberId());
            assertThat(header(deadLetter, KafkaDeadLetterHeaders.CONSUMER_GROUP_ID))
                    .isEqualTo(subscription.consumerGroupId());

            ResolvedMessageSubscription inventorySubscription = new ResolvedMessageSubscription(
                    "inventory-availability",
                    "inventory-kafka-group",
                    Map.of(InventoryEventDestinations.STOCK_EVENTS, "stock-events"));
            var inventoryHandler = KafkaDeadLetterErrorHandlerFactory.create(
                    operations,
                    new FixedBackOff(0, 0),
                    failure -> MessageFailureClassification.retryable(MessageFailureCategory.HANDLER),
                    KafkaDeadLetterHeadersProvider.forSubscription(inventorySubscription));
            assertThat(inventoryHandler.handleOne(
                            new IllegalArgumentException("invalid stock"),
                            new ConsumerRecord<>(InventoryEventDestinations.STOCK_EVENTS, 0, 4L, "sku-1", "stock"),
                            null,
                            null))
                    .isTrue();

            DeadLetterQueryService query = new DeadLetterQueryService(bootstrapServers);
            assertThat(query.list(null, null, 0, 50).items()).hasSize(2);
            var page = query.list(SOURCE, subscription.consumerGroupId(), 0, 50);
            assertThat(page.items()).hasSize(1);
            assertThat(page.items().getFirst().originalTopic()).isEqualTo(SOURCE);
            assertThat(page.items().getFirst().exceptionMessage()).contains("invalid contract");
            assertThat(query.list(SOURCE, "another-group", 0, 50).items()).isEmpty();
            assertThat(query.list(SOURCE, subscription.consumerGroupId(), 1, 50).items())
                    .isEmpty();
            assertThat(query.detail(DLT, deadLetter.partition(), deadLetter.offset())
                            .payload())
                    .isEqualTo(source.value());
            assertThatThrownBy(() -> query.list("unrelated.topic", null, 0, 50))
                    .isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> query.detail(DLT, 0, 9999L)).isInstanceOf(ResponseStatusException.class);
            assertThatThrownBy(() -> query.detail(DLT, 99, 0L)).isInstanceOf(ResponseStatusException.class);

            long rawOffset = template.send(DLT, "raw-key", "raw-payload")
                    .get()
                    .getRecordMetadata()
                    .offset();
            var raw = query.detail(DLT, 0, rawOffset);
            assertThat(raw.record().originalTopic()).isNull();
            assertThat(raw.record().consumerGroupId()).isNull();
            assertThat(raw.payload()).isEqualTo("raw-payload");
        } finally {
            template.destroy();
        }
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }
}
