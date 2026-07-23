package com.flowzati.archone.allocation.infrastructure.repository.jpa;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.infrastructure.entity.StockReservationEntity;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaStockReservationRepository
    extends JpaRepository<StockReservationEntity, UUID> {

  Optional<StockReservationEntity> findByOrderIdAndStatus(
      UUID orderId,
      ReservationStatus status
  );
}
