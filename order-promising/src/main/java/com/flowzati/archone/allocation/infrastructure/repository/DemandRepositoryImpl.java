package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.Demand;
import com.flowzati.archone.allocation.domain.repository.DemandRepository;
import com.flowzati.archone.allocation.infrastructure.entity.DemandLineEntity;
import com.flowzati.archone.allocation.infrastructure.mapper.DemandMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaDemandLineRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
public class DemandRepositoryImpl implements DemandRepository {

  private final JpaDemandLineRepository repository;

  public DemandRepositoryImpl(JpaDemandLineRepository repository) {
    this.repository = repository;
  }

  @Override
  public List<Demand> findOutstandingDemandInFifoOrder(
      UUID ownerId, UUID locationId, String skuCode, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("Limit must be positive");
    }

    // ① 選單：哪幾張單進入本輪，依到達順序。
    List<UUID> orderIds = repository.findOrderIdsWithOutstandingDemand(
        ownerId, locationId, skuCode, Limit.of(limit));
    if (orderIds.isEmpty()) {
      return List.of();
    }

    // ② 取行：那些單還欠的全部行——包含別的 SKU 的。一張單整批配到或整批不配，決策需要看見
    //    整籃；只取命中該 SKU 的行就無從判斷「是否同時可滿足」。
    List<DemandLineEntity> rows =
        repository.findByOrderIdInOrderByOrderIdAscOrderLineIdAsc(orderIds);
    return DemandMapper.toDomain(rows);
  }

  @Override
  public Optional<Demand> findByOrderId(UUID orderId) {
    List<DemandLineEntity> rows = repository.findByOrderIdOrderByOrderLineIdAsc(orderId);
    // 全部配到、或訂單已取消時，view 裡就沒有這張單的任何一列。
    return rows.isEmpty() ? Optional.empty() : Optional.of(DemandMapper.toDomain(rows).getFirst());
  }
}
