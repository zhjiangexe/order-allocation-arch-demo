package com.flowzati.archone.catalog.entrypoint.rest.response;

import com.flowzati.archone.catalog.domain.aggregate.Facility;
import java.util.UUID;

/**
 * 倉庫。只有身分——沒有狀態、能力、產能或覆蓋範圍，因為系統不做選倉決策，那些欄位也就
 * 不存在。契約不帶它們，呼叫端就不會以為有東西可以依據。
 */
public record FacilityResponse(
    UUID facilityId,
    String code,
    String name
) {

  public static FacilityResponse from(Facility facility) {
    return new FacilityResponse(facility.getId(), facility.getCode(), facility.getName());
  }
}
