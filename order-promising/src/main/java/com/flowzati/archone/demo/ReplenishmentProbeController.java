package com.flowzati.archone.demo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowzati.archone.stock.application.event.InventoryEventTopics;
import com.flowzati.archone.stock.application.event.StockReplenishedIntegrationEvent;
import com.flowzati.archone.common.IdGenerator;
import com.flowzati.archone.common.outbox.StockContentionKey;
import java.time.LocalDate;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
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
    // quantity 是包裝型別，缺欄位時是 null。**必須在這裡明確擋下**——直接傳給收 int 的建構子
    // 會在拆箱時 NPE，而 NPE 會變成 500，把呼叫方的錯誤報成伺服器的錯誤。包裝型別的目的正是
    // 讓「沒帶」與「帶了 0」分得開，不檢查就等於白選了那個型別。
    if (request.quantity() == null) {
      throw new IllegalArgumentException("Replenishment quantity is required");
    }
    StockReplenishedIntegrationEvent event = new StockReplenishedIntegrationEvent(
        IdGenerator.nextId(),
        request.ownerId(),
        request.nodeId(),
        request.sku(),
        request.inDate(),
        request.expiryDate(),
        request.quantity());
    kafkaTemplate.send(record(event)).join();
    return ResponseEntity.status(HttpStatus.ACCEPTED).body(
        new ReplenishmentAcceptedResponse(event.getEventId(), event.getSku(), event.getQuantity()));
  }

  /**
   * 缺欄位或值域錯誤一律回 {@code 400}，與 {@code OrderController} 同一個慣例——兩者都是
   * 「呼叫方給了不合法的輸入」。
   *
   * <p>驗證由 {@code StockReplenishedIntegrationEvent} 的建構子完成，這裡不重複一份：那個事件
   * 是要送上 Kafka 的東西，它自己拒絕不合法的內容才是唯一有效的守門。少了這個 handler，缺欄位
   * 會變成 {@code 500}——把呼叫方的錯誤報成伺服器的錯誤。
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<String> handleInvalidRequest(IllegalArgumentException exception) {
    return ResponseEntity.badRequest().body(exception.getMessage());
  }

  /**
   * 訊息必須滿足 {@code KafkaIntegrationEventDispatcher} 的契約：{@code id} 與
   * {@code eventType} 兩個 header、payload 的 eventId 與 header 一致。
   *
   * <p>record key 用 {@link StockContentionKey}（{@code (貨主, 倉)}）。這裡與 ordering 的
   * translator 必須產生逐位元相同的 key，因此共用同一個組成規則而不是各寫一次——不一致的
   * 話補貨與下單會落在不同 partition，兩者對同一列庫存的寫入就不再被序列化。
   */
  private ProducerRecord<String, String> record(StockReplenishedIntegrationEvent event) {
    String key = StockContentionKey.of(event.getOwnerId(), event.getNodeId());
    ProducerRecord<String, String> record =
        new ProducerRecord<>(InventoryEventTopics.STOCK_EVENTS, key, serialize(event));
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

  /**
   * 五個維度都必填——它們合起來決定這批貨加到哪一列，缺任一個就得定義合併規則。
   *
   * <p>包裝型別而非基本型別，是為了讓「沒帶」與「帶了 0」分得開：缺欄位要回 400，帶 0 則是
   * 值域錯誤。用 {@code int} 的話缺欄位會靜默變成 0，然後被當成值域錯誤處理。
   */
  public record ReplenishStockRequest(
      UUID ownerId,
      UUID nodeId,
      String sku,
      LocalDate inDate,
      LocalDate expiryDate,
      Integer quantity
  ) {
  }

  public record ReplenishmentAcceptedResponse(UUID eventId, String sku, int quantity) {
  }
}
