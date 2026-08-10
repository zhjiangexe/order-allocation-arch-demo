package com.flowzati.archone.testsupport;

import com.flowzati.archone.messaging.api.Message;
import com.flowzati.archone.messaging.kafka.KafkaMessageMapper;
import com.flowzati.archone.stock.application.event.PromisingEventTopics;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 把 outbox 裡的配貨結果事件餵回 ordering 的 consumer。
 *
 * <p><b>為什麼需要它。</b>配貨不再直接改 {@code Order}——它寫自己的表、發事件，由 ordering
 * 收到後推進訂單狀態。production 裡 Debezium 負責把 outbox 的列送上 Kafka，而 SIT 沒有
 * Debezium，事件會停在 outbox，訂單狀態永遠不動。
 *
 * <p>所以斷言「訂單最終是 ALLOCATED」的整合測試必須自己走完那一段。與其把那些斷言改成只看
 * 預留與 outbox，這裡選擇補完鏈路——因為 R4 的重點正是「這條鏈接得起來」，而只驗 outbox 有
 * 事件並不能證明 ordering 消費得了它（欄位名對不對、handler 註冊了沒有，都不會被發現）。
 *
 */
public final class AllocationOutcomeDrain {

  private final JdbcTemplate jdbcTemplate;
  private final KafkaMessageMapper messageMapper;
  private final BiConsumer<String, Message> emitter;
  private final Set<UUID> consumed = new HashSet<>();

  AllocationOutcomeDrain(
      JdbcTemplate jdbcTemplate,
      KafkaMessageMapper messageMapper,
      BiConsumer<String, Message> emitter
  ) {
    this.jdbcTemplate = jdbcTemplate;
    this.messageMapper = messageMapper;
    this.emitter = emitter;
  }

  /**
   * 把目前 outbox 裡尚未餵過的配貨結果事件全部送進 ordering，回傳送了幾則。
   *
   * <p>依 {@code timestamp} 排序：ordering 對同一張單可能同時收到缺貨與配到，順序錯了狀態
   * 就會停在錯的地方。production 裡靠 partition key（同一個 orderId）保證這個順序。
   */
  public int drain() {
    List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT id, type, partition_key, payload, headers FROM event_outbox
         WHERE route = ?
         ORDER BY timestamp, id
        """, PromisingEventTopics.ALLOCATION_EVENTS);

    int delivered = 0;
    for (Map<String, Object> row : rows) {
      UUID eventId = UUID.fromString(row.get("id").toString());
      if (!consumed.add(eventId)) {
        continue;
      }
      dispatch(
          eventId,
          row.get("type").toString(),
          row.get("partition_key").toString(),
          row.get("payload").toString(),
          row.get("headers").toString());
      delivered++;
    }
    return delivered;
  }

  private void dispatch(
      UUID eventId,
      String eventType,
      String partitionKey,
      String payload,
      String serializedHeaders
  ) {
    ConsumerRecord<String, String> record = new ConsumerRecord<>(
        PromisingEventTopics.ALLOCATION_EVENTS, 0, 0, partitionKey, payload);
    record.headers().add(
        KafkaMessageMapper.LEGACY_ID_HEADER,
        eventId.toString().getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.LEGACY_EVENT_TYPE_HEADER,
        eventType.getBytes(StandardCharsets.UTF_8));
    record.headers().add(
        KafkaMessageMapper.SERIALIZED_HEADERS,
        serializedHeaders.getBytes(StandardCharsets.UTF_8));
    emitter.accept(record.topic(), messageMapper.map(record));
  }
}
