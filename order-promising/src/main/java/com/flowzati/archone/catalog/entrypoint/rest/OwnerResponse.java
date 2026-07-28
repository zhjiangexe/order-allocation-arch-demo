package com.flowzati.archone.catalog.entrypoint.rest;

import com.flowzati.archone.catalog.domain.model.Owner;
import java.util.UUID;

/**
 * 貨主。{@code name} 一併回傳而不只給識別碼——每個提到貨主的畫面都要顯示它，只回識別碼
 * 會逼呼叫端為每一列再打一次查詢。
 */
public record OwnerResponse(
    UUID ownerId,
    String code,
    String name
) {

  static OwnerResponse from(Owner owner) {
    return new OwnerResponse(
        owner.getId(),
        owner.getCode(),
        owner.getName());
  }
}
