package com.flowzati.archone.foundation.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 識別碼必須是時間有序的。
 *
 * <p><b>待配佇列的 FIFO 順序完全架在這件事上。</b>佇列以 {@code order_id} 排序，而它之所以
 * 等於「訂單進入系統的順序」，唯一的理由就是 UUID v7 把時間戳編在主鍵的高位。
 *
 * <p>換回 v4（{@code UUID.randomUUID()}）之後，佇列會靜默變成亂序：不拋錯、不留 log，配貨
 * 照常進行、庫存數字照常正確，只是先來的客戶不再先拿到貨。**除了這支測試，沒有任何東西會
 * 變紅**——其他測試斷言的是「哪些單被配到」與「有沒有超賣」，不是順序。
 */
class IdGeneratorTest {

  @Test
  @DisplayName("連續產生的識別碼應遞增——佇列的 FIFO 順序靠這個性質成立")
  void generatesTimeOrderedIdentifiers() {
    List<UUID> ids = IntStream.range(0, 1_000).mapToObj(i -> IdGenerator.nextId()).toList();

    assertThat(ids).isSorted();
  }

  @Test
  @DisplayName("同一毫秒內產生的識別碼也應遞增——v7 的單調計數器要真的生效")
  void keepsOrderWithinTheSameMillisecond() {
    // 一千個 id 在現代硬體上遠快於 1 毫秒，所以這一批必然大量落在同一個時間戳裡。
    // v7 靠時間戳之後的計數器區分它們；少了那段，同毫秒的順序會由隨機位元決定。
    List<UUID> ids = IntStream.range(0, 1_000).mapToObj(i -> IdGenerator.nextId()).toList();

    long distinctTimestamps = ids.stream()
        .map(id -> id.getMostSignificantBits() >>> 16)
        .distinct()
        .count();
    assertThat(distinctTimestamps)
        .withFailMessage("這批 id 分散在 %d 個毫秒上，測不到同毫秒的順序", distinctTimestamps)
        .isLessThan(ids.size());

    assertThat(ids).isSorted();
  }

  @Test
  @DisplayName("識別碼應為 UUID 版本 7——版本號變了就不再有時間順序")
  void producesVersionSevenIdentifiers() {
    assertThat(IdGenerator.nextId().version()).isEqualTo(7);
  }
}
