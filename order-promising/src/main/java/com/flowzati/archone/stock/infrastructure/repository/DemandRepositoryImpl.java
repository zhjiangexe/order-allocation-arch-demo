package com.flowzati.archone.stock.infrastructure.repository;

import com.flowzati.archone.stock.domain.model.Demand;
import com.flowzati.archone.stock.domain.repository.DemandRepository;
import com.flowzati.archone.stock.infrastructure.entity.DemandLineEntity;
import com.flowzati.archone.stock.infrastructure.mapper.DemandMapper;
import com.flowzati.archone.stock.infrastructure.repository.jpa.JpaDemandLineRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class DemandRepositoryImpl implements DemandRepository {

  private final JpaDemandLineRepository repository;

  public DemandRepositoryImpl(JpaDemandLineRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<Demand> findByOrderId(UUID orderId) {
    List<DemandLineEntity> rows = repository.findByOrderIdOrderByOrderLineIdAsc(orderId);
    // 全部配到、或訂單已取消時，view 裡就沒有這張單的任何一列。
    return rows.isEmpty() ? Optional.empty() : Optional.of(DemandMapper.toDomain(rows).getFirst());
  }
}
