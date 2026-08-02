package com.flowzati.archone.ordering.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 上游對這張單的配送要求：從哪個倉出、送到哪、何時要到。
 *
 * <p>收單當下給定、之後不再改變，與訂單的狀態和時間戳是不同性質的東西，因此收成一個
 * 型別而不是散在 {@link Order} 上。貨主與上游單號則留在 {@link Order} 頂層——它們是訂單的
 * 識別，且 {@code ownerId} 是整個系統最常讀的欄位，多包一層只會讓每個呼叫端多一次跳轉。
 *
 * <p>{@code fulfillmentNodeId} 是**必填**。3PL 的出貨倉由合約決定、由上游在下單時指定，
 * 系統照做——它不是一個可能被推翻的請求，因此既不叫 requested，也不可為空。可空等於在
 * 型別上保留一個永遠不會發生的狀態，而每個讀取端都得處理它。
 *
 * <p>{@code shipToZone} 與 {@code shipToAddress} 分開存而不是只留完整地址：後者供履約與
 * 面單使用，前者是地址的粗粒度形式。合併會讓需要分區的一方從地址字串裡剖析。
 *
 * <p><b>{@code promisedDeliveryDate} 是上游給的參考值，本系統目前不據以決策。</b>它被儲存、
 * 隨事件傳遞、由 REST 揭露，但沒有任何一行程式碼拿它做判斷——配貨不看它，缺貨佇列也不依
 * 它排序（佇列依到達順序）。
 *
 * <p>那不是遺漏，是這個系統目前只回答承諾的第一個問題（有沒有貨），而不回答第三個（什麼
 * 時候到）。第三個問題需要出貨前置時間、承運商時效、截單時間與行事曆——**一個都還沒有**，
 * 所以它是加一個模型而不是加一個欄位。
 *
 * <p>屆時第一件會變的是**缺貨佇列的排序鍵**：從到達順序改為承諾交期。一張今天到期的單該
 * 排在昨天下的、下週才要的單前面，而那也會一併修正「新單插隊拿走補貨餘量」這個目前已知
 * 且刻意接受的取捨。
 */
public record DeliveryTerms(
    UUID fulfillmentNodeId,
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate
) {

  public DeliveryTerms {
    if (fulfillmentNodeId == null) {
      throw new IllegalArgumentException("Fulfillment node is required");
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
  }
}
