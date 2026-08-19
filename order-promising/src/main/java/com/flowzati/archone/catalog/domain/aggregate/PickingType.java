package com.flowzati.archone.catalog.domain.aggregate;

import com.flowzati.archone.catalog.domain.type.PickingDirection;

import java.util.UUID;

/**
 * 作業類型：這種搬運從哪到哪。
 *
 * <p>它存在的理由是讓「新增一種流程」成為**新增一筆資料**而不是改程式。要擋的是流程種類被
 * 編碼成 enum 值那個病——既有系統的出庫類型長到 18 種、狀態欄位長到 28 個值，就是那樣累積的。
 *
 * <p><b>但它不說「下一段是誰」。</b> 作業類型之間沒有任何「後繼」的欄位；把多段串起來是規則
 * 引擎的事，而本系統沒有多段。分界是：作業類型描述「一段作業長什麼樣」，規則描述「在什麼
 * 需求下、會有哪幾段、順序如何」。
 *
 * <p>與位置同一類：參考資料、沒有寫入介面、由 seed 建立，因此住在 catalog。
 */
public record PickingType(
    UUID id,
    UUID facilityId,
    PickingDirection code,
    String name,
    UUID defaultFromLocationId,
    UUID defaultToLocationId
) {

  public PickingType {
    if (id == null || facilityId == null || code == null) {
      throw new IllegalArgumentException("Picking type requires an id, a facility and a code");
    }
    if (name == null || name.isBlank()) {
      throw new IllegalArgumentException("Picking type name is required");
    }
    // 預設起訖兩端都要有，與 move 的判準相同：一段作業必須說得出從哪到哪。
    if (defaultFromLocationId == null || defaultToLocationId == null) {
      throw new IllegalArgumentException("A picking type must say where its work runs between");
    }
  }
}
