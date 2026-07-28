package com.flowzati.archone.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.allocation.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.messaging.IntegrationEventTopics;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 扮演外部 Inventory bounded context 的上游 producer，向 {@code inventory.stock-events}
 * 發布真實的補貨事件。本 repo 只消費那個 topic、不擁有它，所以這支探針不屬於任何 bounded
 * context，放在 {@code demo} 並以 dev profile 限定。
 *
 * <p><b>為什麼不直接呼叫 ReplenishmentUsecase：</b>該 usecase 收的是含
 * {@code MessageMetadata} 的 inbound command，而那個 metadata 正是 inbox 冪等所依據的憑證
 * ——直接呼叫等於自行偽造。繞過 Kafka 也會一併繞過重試、退避與 DLT 處理，而那是這個系統
 * 最值得展示的部分。
 *
 * <p><b>為什麼不經 outbox：</b>outbox 解決的是「本地狀態變更」與「事件發布」的原子性。
 * 這支探針不變更任何本地狀態，沒有需要對齊的 transaction，因此直接發布是正確的，不是
 * 違反本專案的 outbox 原則。
 */
@RestController
@RequestMapping("/demo")
@Profile("dev")
public class ReplenishmentProbeController {

  private final KafkaTemplate<String, String> kafkaTemplate;
  private final ObjectMapper objectMapper;

  public ReplenishmentProbeController(
      KafkaTemplate<String, String> kafkaTemplate,
      ObjectMapper objectMapper
  ) {
    this.kafkaTemplate = kafkaTemplate;
    this.objectMapper = objectMapper;
  }

  /**
   * 回 202 而非 200：發布成功不代表配置完成，配置結果只能由後續查詢觀察。
   *
   * <p>刻意等待 broker 回應再回傳——若發布失敗就讓例外浮出成 5xx。探針的價值在於誠實
   * 反映訊息路徑的狀態，回一個「已受理」卻其實沒送出去，比直接失敗更糟。
   *
   * <p>回應不含「預期會喚醒幾張訂單」：那是發布前的快照，與實際結果可能不符。
   */
  @PostMapping("/replenish")
  public ResponseEntity<ReplenishmentAcceptedResponse> replenish(
      @RequestBody ReplenishStockRequest request
  ) {
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(
        IdGenerator.nextId(), request.ownerId(), request.sku(), request.quantity());
    kafkaTemplate.send(record(event)).join();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(
        new ReplenishmentAcceptedResponse(event.getEventId(), event.getSku(), event.getQuantity()));
  }

  /**
   * 訊息必須滿足 {@code KafkaIntegrationEventDispatcher} 的契約：{@code id} 與
   * {@code eventType} 兩個 header、payload 的 eventId 與 header 一致。
   *
   * <p>record key 維持裸 SKU，即使事件現在帶了貨主——key 決定的是 partition，而它的正確
   * 形狀由 {@code StockPool} 的識別決定。庫存目前仍以 {@code (sku)} 唯一，同碼 SKU 真的
   * 共用一列，因此裸 SKU 才是讓競爭者收斂到同一個 partition 的正確 key。
   */
  private ProducerRecord<String, String> record(StockReplenishedIntegrationEvent event) {
    ProducerRecord<String, String> record = new ProducerRecord<>(
        IntegrationEventTopics.INVENTORY_STOCK_EVENTS_TOPIC, event.getSku(), serialize(event));
    record.headers().add("id", bytes(event.getEventId().toString()));
    record.headers().add("eventType", bytes(StockReplenishedIntegrationEvent.class.getSimpleName()));
    return record;
  }

  private String serialize(StockReplenishedIntegrationEvent event) {
    try {
      return objectMapper.writeValueAsString(event);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("Cannot serialize replenishment probe event", exception);
    }
  }

  private static byte[] bytes(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /** {@code ownerId} 決定要喚醒哪一個貨主的缺貨佇列——SKU 代碼跨貨主撞號，只憑它決定不了。 */
  public record ReplenishStockRequest(java.util.UUID ownerId, String sku, Integer quantity) {
  }

  public record ReplenishmentAcceptedResponse(UUID eventId, String sku, int quantity) {
  }
}
