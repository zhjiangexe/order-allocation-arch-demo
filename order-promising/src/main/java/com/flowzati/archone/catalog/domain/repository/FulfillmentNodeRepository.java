package com.flowzati.archone.catalog.domain.repository;

import com.flowzati.archone.catalog.domain.model.FulfillmentNode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FulfillmentNodeRepository {

  void save(FulfillmentNode node);

  /**
   * 把倉庫指派給貨主。
   *
   * <p>指派沒有自己的身分也沒有屬性，因此不是一個 aggregate，也不值得一個獨立的
   * repository——它是倉庫主檔的一部分，放在這裡。可否換批號之類的設定日後掛上來時，
   * 這個判斷要重新評估：帶設定的配對就有了自己的身分。
   */
  void assign(UUID ownerId, UUID nodeId);

  Optional<FulfillmentNode> findById(UUID nodeId);

  Optional<FulfillmentNode> findByCode(String code);

  /**
   * 列出某貨主已指派的倉庫，以倉庫編碼遞增排序。
   *
   * <p>沒有「列出全部倉庫」的方法，這是刻意的：倉庫要能被指定的前提是貨主掛了它，一份
   * 不帶貨主的清單會誘使呼叫端提供該貨主出不了貨的倉。
   */
  List<FulfillmentNode> findByOwner(UUID ownerId);
}
