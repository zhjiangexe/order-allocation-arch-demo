package com.flowzati.archone.catalog.infrastructure.repository.jpa;

import com.flowzati.archone.catalog.infrastructure.entity.FulfillmentNodeEntity;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaFulfillmentNodeRepository extends JpaRepository<FulfillmentNodeEntity, UUID> {

  Optional<FulfillmentNodeEntity> findByCode(String code);

  /**
   * 以指派關係為起點取倉庫，順序由 {@code code} 決定。
   *
   * <p>排序不可省略：下拉選單每次載入的順序必須一致，否則畫面上的選項會無故跳動。
   */
  @Query("""
      SELECT n FROM FulfillmentNodeEntity n
      WHERE EXISTS (
        SELECT 1 FROM OwnerNodeEntity a
        WHERE a.id.nodeId = n.id AND a.id.ownerId = :ownerId
      )
      ORDER BY n.code ASC
      """)
  List<FulfillmentNodeEntity> findAssignedTo(@Param("ownerId") UUID ownerId);
}
