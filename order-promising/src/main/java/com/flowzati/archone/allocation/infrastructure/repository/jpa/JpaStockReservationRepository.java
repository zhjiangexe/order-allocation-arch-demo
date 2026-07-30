package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockReservationRepository
    extends JpaRepository<StockReservationEntity, UUID> {

  List<StockReservationEntity> findByOrderLineIdInAndStatus(
      Collection<UUID> orderLineIds,
      ReservationStatus status
  );
}
