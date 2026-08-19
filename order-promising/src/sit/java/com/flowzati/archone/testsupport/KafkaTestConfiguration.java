package com.flowzati.archone.testsupport;

import com.flowzati.archone.contracts.inventory.v1.InventoryChannels;
import com.flowzati.archone.contracts.ordering.v1.OrderingChannels;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

/** 供需要真實 broker 的 SIT 使用；多數 SIT 走 in-process 捷徑，不需要這個。 */
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestConfiguration {

  private static final String KAFKA_IMAGE = "apache/kafka-native:4.1.2";

  @Bean
  @ServiceConnection
  KafkaContainer kafkaContainer() {
    return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));
  }

  /**
   * 先把 topic 建起來再讓 listener 訂閱。正式環境的 topic 本來就先於 app 存在；若留給
   * 首次發布時自動建立，consumer 要等下一次 metadata 更新才會發現它，測試會變成時好時壞。
   */
  @Bean
  NewTopic inventoryStockEventsTopic() {
    return new NewTopic(InventoryChannels.STOCK_EVENTS, 1, (short) 1);
  }

  @Bean
  NewTopic orderingOrderEventsTopic() {
    return new NewTopic(OrderingChannels.ORDER_EVENTS, 1, (short) 1);
  }
}
