package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.Inbox;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReleaseReservationUsecase {

  private final Inbox inbox;
  private final StockReservationRepository stockReservationRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;

  public ReleaseReservationUsecase(
      Inbox inbox,
      StockReservationRepository stockReservationRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock) {
    this.inbox = inbox;
    this.stockReservationRepository = stockReservationRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
  }

  @Transactional
  public void handle(ReleaseReservationCommand command, UUID messageId) {
    if (!inbox.claimIfNew(messageId)) {
      return;
    }
    Optional<StockReservation> activeByOrderId = stockReservationRepository.findActiveByOrderId(command.orderId());
    if (activeByOrderId.isEmpty()) {
      return;
    }
    StockReservation reservation = activeByOrderId.get();

    StockPool stockPool = stockPoolRepository.findById(reservation.getStockPoolId())
        .orElseThrow(() -> new IllegalStateException("StockPool not found: " + reservation.getStockPoolId()));

    Instant now = clock.instant();
    allocationCoordinator.releaseReservation(reservation, stockPool, now);
  }
}
