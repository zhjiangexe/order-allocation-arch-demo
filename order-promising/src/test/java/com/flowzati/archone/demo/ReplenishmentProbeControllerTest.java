package com.flowzati.archone.demo;

import com.flowzati.archone.common.configuration.CommonConfiguration;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResultAssert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@WebMvcTest(ReplenishmentProbeController.class)
@Import(CommonConfiguration.class)
@ActiveProfiles("dev")
class ReplenishmentProbeControllerTest {

  /** 補貨要指定貨主——SKU 代碼跨貨主撞號，只憑它決定不了要喚醒誰的缺貨佇列。 */
  private static final String REPLENISH_BODY = """
      {
        "ownerId": "00000000-0000-0000-0000-0000000000a1",
        "sku": "HOT-SKU",
        "quantity": 500
      }
      """;

  @Autowired
  private MockMvcTester mvc;

  @MockitoBean
  private KafkaTemplate<String, String> kafkaTemplate;

  @Test
  @DisplayName("補貨探針發布的訊息應符合 consumer 的訊息契約")
  void shouldPublishMessageSatisfyingConsumerContract() {
    when(kafkaTemplate.send(any(ProducerRecord.class)))
        .thenReturn(CompletableFuture.completedFuture(null));

    MvcTestResultAssert response = assertThat(mvc.post().uri("/demo/replenish")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REPLENISH_BODY));

    response.hasStatus(202);
    response.bodyJson().extractingPath("$.sku").isEqualTo("HOT-SKU");
    response.bodyJson().extractingPath("$.quantity").isEqualTo(500);
    // 補貨是非同步的，回應不得預告會喚醒幾張訂單——那是發布前的快照，可能與實際結果不符
    response.bodyJson().doesNotHavePath("$.expectedWokenOrders");

    ArgumentCaptor<ProducerRecord<String, String>> record = ArgumentCaptor.captor();
    verify(kafkaTemplate).send(record.capture());
    ProducerRecord<String, String> published = record.getValue();
    String eventId = header(published, "id");

    assertThat(published.topic()).isEqualTo(IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC);
    assertThat(published.key()).isEqualTo("HOT-SKU");
    assertThat(header(published, "eventType")).isEqualTo("StockReplenishedIntegrationEvent");
    assertThat(published.value())
        .contains("\"eventId\":\"" + eventId + "\"")
        .contains("\"sku\":\"HOT-SKU\"")
        .contains("\"quantity\":500");
    // 回應帶回同一個 eventId，呼叫方才能把非同步結果對回這次觸發
    response.bodyJson().extractingPath("$.eventId").isEqualTo(eventId);
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }
}
