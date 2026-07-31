package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.ReservationStatus;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.allocation.infrastructure.mapper.StockReservationMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockReservationRepository;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Repository;

@Repository
public class StockReservationRepositoryImpl implements StockReservationRepository {

  private final JpaStockReservationRepository repository;

  public StockReservationRepositoryImpl(JpaStockReservationRepository repository) {
    this.repository = repository;
  }

  @Override
  public void save(StockReservation reservation) {
    repository.save(StockReservationMapper.toEntity(reservation));
  }


  @Override
  public List<StockReservation> findActiveByOrderId(UUID orderId) {
    return repository.findByOrderIdAndStatus(orderId, ReservationStatus.ACTIVE)
        .stream()
        .map(StockReservationMapper::toDomain)
        .toList();
  }
}
