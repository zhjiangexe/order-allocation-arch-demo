package com.flowzati.archone.allocation.domain.repository;

import com.flowzati.archone.allocation.domain.model.StockReservation;
import java.util.Optional;
import java.util.UUID;

public interface StockReservationRepository {

  void save(StockReservation reservation);

  Optional<StockReservation> findActiveByOrderId(UUID orderId);
}
