package com.flowzati.archone.demo;

import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.common.configuration.CommonConfiguration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@WebMvcTest(ReplenishmentProbeController.class)
@Import(CommonConfiguration.class)
@ActiveProfiles("dev")
class ReplenishmentProbeControllerTest {

  private static final String OWNER_ID = "00000000-0000-0000-0000-0000000000a1";
  private static final String FACILITY_ID = "00000000-0000-0000-0000-0000000000b1";

  /**
   * 五個維度都要帶：貨主、倉、SKU、入庫日、效期。它們合起來決定這批貨加到哪一列，缺任一個
   * 就得定義合併規則，而任何一條規則都會在某些情況下把不可互換的貨併在一起。
   */
  private static final String REPLENISH_BODY = """
      {
        "ownerId": "00000000-0000-0000-0000-0000000000a1",
        "facilityId": "00000000-0000-0000-0000-0000000000b1",
        "sku": "HOT-SKU",
        "inDate": "2026-01-05",
        "expiryDate": "2026-12-31",
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

    assertThat(published.topic()).isEqualTo(InventoryEventTopics.STOCK_EVENTS);
    // key 是爭用群組 (貨主, 倉)，不含 SKU。與 ordering 的 translator 必須逐位元相同，
    // 否則補貨與下單落在不同 partition，對同一列庫存的寫入就不再被序列化。
    assertThat(published.key()).isEqualTo(OWNER_ID + "/" + FACILITY_ID);
    assertThat(header(published, "eventType")).isEqualTo("StockReplenishedIntegrationEvent");
    assertThat(published.value())
        .contains("\"eventId\":\"" + eventId + "\"")
        .contains("\"sku\":\"HOT-SKU\"")
        .contains("\"quantity\":500")
        .contains("\"inDate\":\"2026-01-05\"")
        .contains("\"expiryDate\":\"2026-12-31\"");
    // 回應帶回同一個 eventId，呼叫方才能把非同步結果對回這次觸發
    response.bodyJson().extractingPath("$.eventId").isEqualTo(eventId);
  }

  private static String header(ProducerRecord<String, String> record, String name) {
    var header = record.headers().lastHeader(name);
    return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
  }

  @ParameterizedTest(name = "[{index}] 缺 {0}")
  @ValueSource(strings = {"ownerId", "facilityId", "sku", "inDate", "expiryDate", "quantity"})
  @DisplayName("五個維度或數量缺任一個都應回 400，且不得發出任何訊息")
  void shouldRejectARequestMissingAnyRequiredField(String missingField) {
    MvcTestResultAssert response = assertThat(mvc.post().uri("/demo/replenish")
        .contentType(MediaType.APPLICATION_JSON)
        .content(bodyWithout(missingField)));

    // 400 而不是 500：缺欄位是呼叫方的錯，與 OrderController 同一個慣例。少了那個
    // ExceptionHandler 這裡會是 500——把呼叫方的錯誤報成伺服器的錯誤。
    response.hasStatus(400);
    // 無效的補貨不該換來一次沒有必要的往返，更不該讓半個訊息流出去。
    verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
  }

  @Test
  @DisplayName("數量非正數應回 400——那是值域錯誤，與缺欄位不同但同樣是呼叫方的錯")
  void shouldRejectANonPositiveQuantity() {
    assertThat(mvc.post().uri("/demo/replenish")
        .contentType(MediaType.APPLICATION_JSON)
        .content(REPLENISH_BODY.replace("\"quantity\": 500", "\"quantity\": 0")))
        .hasStatus(400);
    verify(kafkaTemplate, never()).send(any(ProducerRecord.class));
  }

  /**
   * 把某一個欄位**整個拿掉**後重新組出 JSON，而不是把它設成 {@code null}。
   *
   * <p>兩者在 Jackson 之後都是 null，但「整個不存在」才是真實呼叫方會送出的形狀。
   *
   * <p>以 map 重新序列化而不是對字串動刀：刪一行會留下懸空的逗號，那樣測到的是「Jackson 拒絕
   * 壞掉的 JSON」而不是「controller 拒絕缺欄位的請求」——兩者都回 400，所以那個錯誤不會被
   * 這支測試發現。
   */
  private static String bodyWithout(String field) {
    try {
      Map<String, Object> body = new LinkedHashMap<>(
          new ObjectMapper().readValue(REPLENISH_BODY, new TypeReference<>() {}));
      assertThat(body.remove(field)).as("欄位名寫錯了：%s", field).isNotNull();
      return new ObjectMapper().writeValueAsString(body);
    } catch (Exception exception) {
      throw new IllegalStateException("Cannot build request body", exception);
    }
  }
}
