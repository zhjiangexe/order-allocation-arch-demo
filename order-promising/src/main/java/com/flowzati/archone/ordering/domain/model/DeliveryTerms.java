package com.flowzati.archone.ordering.domain.model;

import java.time.LocalDate;
import java.time.Instant;
import java.util.UUID;

/**
 * 上游對這張單的配送要求：從哪個倉出、送到哪、何時要到。
 *
 * <p>收單當下給定、之後不再改變，與訂單的狀態和時間戳是不同性質的東西，因此收成一個
 * 型別而不是散在 {@link Order} 上。貨主與上游單號則留在 {@link Order} 頂層——它們是訂單的
 * 識別，且 {@code ownerId} 是整個系統最常讀的欄位，多包一層只會讓每個呼叫端多一次跳轉。
 *
 * <p>{@code facilityId} 是**必填**。3PL 的出貨倉由合約決定、由上游在下單時指定，
 * 系統照做——它不是一個可能被推翻的請求，因此既不叫 requested，也不可為空。可空等於在
 * 型別上保留一個永遠不會發生的狀態，而每個讀取端都得處理它。
 *
 * <p>{@code shipToZone} 與 {@code shipToAddress} 分開存而不是只留完整地址：後者供履約與
 * 面單使用，前者是地址的粗粒度形式。合併會讓需要分區的一方從地址字串裡剖析。
 *
 * <p>{@code promisedDeliveryDate} 是對客承諾日期；{@code dispatchBy} 是上游排程算出的最晚離倉
 * 時刻。兩者不可互推：前者缺少承運時效、截單時間與行事曆，WMS 不得自行猜出後者。
 *
 * <p>{@code releasePriority} 是上游給 WMS wave planning 的 0..100 排程事實。它不是訂單狀態，
 * 也不改變 allocation 的 FEFO／ship-complete 決策；目前只有 fulfillment handoff 與 WMS 使用。
 *
 * <p>屆時第一件會變的是**缺貨佇列的排序鍵**：從到達順序改為承諾交期。一張今天到期的單該
 * 排在昨天下的、下週才要的單前面，而那也會一併修正「新單插隊拿走補貨餘量」這個目前已知
 * 且刻意接受的取捨。
 */
public record DeliveryTerms(
    UUID facilityId,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    Instant dispatchBy,
    int releasePriority
) {

  public DeliveryTerms {
    if (facilityId == null) {
      throw new IllegalArgumentException("Facility is required");
    }
    if (shipToZone == null || shipToZone.isBlank()) {
      throw new IllegalArgumentException("Ship-to zone is required");
    }
    if (shipToAddress == null || shipToAddress.isBlank()) {
      throw new IllegalArgumentException("Ship-to address is required");
    }
    if (promisedDeliveryDate == null) {
      throw new IllegalArgumentException("Promised delivery date is required");
    }
    if (dispatchBy == null) {
      throw new IllegalArgumentException("Dispatch deadline is required");
    }
    if (releasePriority < 0 || releasePriority > 100) {
      throw new IllegalArgumentException("Release priority must be between 0 and 100");
    }
  }
}
