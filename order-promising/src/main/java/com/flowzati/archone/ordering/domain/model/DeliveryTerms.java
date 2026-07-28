package com.flowzati.archone.ordering.domain.model;

import java.time.LocalDate;
import java.util.UUID;

/**
 * 上游對這張單的配送要求：送到哪、何時要到、（可選）指定從哪出。
 *
 * <p>收單當下給定、之後不再改變，與訂單的狀態和時間戳是不同性質的東西，因此收成一個
 * 型別而不是散在 {@link Order} 上。貨主與上游單號則留在 {@link Order} 頂層——它們是訂單的
 * 識別，且 {@code ownerId} 是整個系統最常讀的欄位，多包一層只會讓每個呼叫端多一次跳轉。
 *
 * <p>{@code shipToZone} 與 {@code shipToAddress} 分開存而不是只留完整地址：前者是 R6 選點的
 * 決策輸入，後者供履約與面單使用，sourcing 不看。合併會讓 R6 得從地址字串裡剖析分區。
 *
 * <p>{@code requestedNodeId} 是貨主指定的出貨倉，指定則 R6 跳過選點。本階段只收下不使用。
 */
public record DeliveryTerms(
    String shipToZone,
    String shipToAddress,
    LocalDate promisedDeliveryDate,
    UUID requestedNodeId
) {

  public DeliveryTerms {
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
