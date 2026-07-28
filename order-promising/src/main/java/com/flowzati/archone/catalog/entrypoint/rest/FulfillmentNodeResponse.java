package com.flowzati.archone.catalog.entrypoint.rest;

import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import java.util.UUID;

/**
 * 倉庫。只有身分——沒有狀態、能力、產能或覆蓋範圍，因為系統不做選倉決策，那些欄位也就
 * 不存在。契約不帶它們，呼叫端就不會以為有東西可以依據。
 */
public record FulfillmentNodeResponse(
    UUID nodeId,
    String code,
    String name
) {

  static FulfillmentNodeResponse from(FulfillmentNode node) {
    return new FulfillmentNodeResponse(node.getId(), node.getCode(), node.getName());
  }
}
