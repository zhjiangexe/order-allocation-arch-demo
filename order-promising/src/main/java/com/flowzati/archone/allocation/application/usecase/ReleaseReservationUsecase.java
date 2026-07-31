package com.flowzati.archone.allocation.application.usecase;

import com.flowzati.archone.allocation.application.command.ReleaseReservationCommand;
import com.flowzati.archone.allocation.application.coordinator.OrderAllocationCoordinator;
import com.flowzati.archone.allocation.domain.model.StockPool;
import com.flowzati.archone.allocation.domain.model.StockReservation;
import com.flowzati.archone.allocation.domain.repository.StockPoolRepository;
import com.flowzati.archone.allocation.domain.repository.StockReservationRepository;
import com.flowzati.archone.common.inbox.InboxRepo;
import com.flowzati.archone.common.inbox.InboundCommand;
import com.flowzati.archone.common.inbox.MessageMetadata;
import jakarta.transaction.Transactional;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ReleaseReservationUsecase {

  private final InboxRepo inboxRepo;
  private final StockReservationRepository stockReservationRepository;
  private final StockPoolRepository stockPoolRepository;
  private final OrderAllocationCoordinator allocationCoordinator;
  private final Clock clock;

  public ReleaseReservationUsecase(
      InboxRepo inboxRepo,
      StockReservationRepository stockReservationRepository,
      StockPoolRepository stockPoolRepository,
      OrderAllocationCoordinator allocationCoordinator,
      Clock clock) {
    this.inboxRepo = inboxRepo;
    this.stockReservationRepository = stockReservationRepository;
    this.stockPoolRepository = stockPoolRepository;
    this.allocationCoordinator = allocationCoordinator;
    this.clock = clock;
  }

  @Transactional
  public void handle(InboundCommand<ReleaseReservationCommand> inbound) {
    if (!inboxRepo.claimIfNew(inbound.message())) {
      return;
    }
    ReleaseReservationCommand command = inbound.command();

    // 直接以訂單查預留。stock_reservations 記著 order_id，所以 allocation 自己回答得了
    // 「這張單有哪些預留」——不必為此去讀 ordering 的訂單，那正是這個 change 要斷開的方向。
    //
    // 也不能改查 demand_lines：那個 view 只有「還欠的」行，而要釋放的恰恰是**已經配到**的
    // 那些，它們早就從 view 裡消失了。
    //
    // 一條行跨三批就有三筆預留，全部都要釋放。只放第一筆的話其餘批的量會永遠鎖著，而且
    // 不會有任何錯誤浮現——庫存看起來只是莫名其妙少了一些。
    List<StockReservation> reservations =
        stockReservationRepository.findActiveByOrderId(command.orderId());
    if (reservations.isEmpty()) {
      return;
    }

    Map<UUID, StockPool> batchesById = new LinkedHashMap<>();
    for (StockReservation reservation : reservations) {
      batchesById.computeIfAbsent(reservation.getStockPoolId(), id ->
          stockPoolRepository.findById(id)
              .orElseThrow(() -> new IllegalStateException("StockPool not found: " + id)));
    }

    Instant now = clock.instant();
    allocationCoordinator.releaseReservations(reservations, batchesById, now);
  }
}
