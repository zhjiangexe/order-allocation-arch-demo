package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.model.Facility;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FacilityRepository {

  void save(Facility facility);

  /**
   * 把物流設施指派給貨主。
   *
   * <p>指派沒有自己的身分也沒有屬性，因此不是一個 aggregate，也不值得一個獨立的
   * repository——它是設施主檔的一部分，放在這裡。可否換批號之類的設定日後掛上來時，
   * 這個判斷要重新評估：帶設定的配對就有了自己的身分。
   */
  void assign(UUID ownerId, UUID facilityId);

  Optional<Facility> findById(UUID facilityId);

  Optional<Facility> findByCode(String code);

  /**
   * 列出某貨主已指派的設施，以設施編碼遞增排序。
   *
   * <p>沒有「列出全部設施」的方法，這是刻意的：設施要能被指定的前提是貨主掛了它，一份
   * 不帶貨主的清單會誘使呼叫端提供未指派的設施。
   */
  List<Facility> findByOwner(UUID ownerId);
}
