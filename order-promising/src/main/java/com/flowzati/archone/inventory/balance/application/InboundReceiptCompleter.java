package com.flowzati.archone.inventory.balance.application;

import com.flowzati.archone.catalog.domain.type.LocationUsage;
import com.flowzati.archone.catalog.domain.aggregate.StockLocation;
import com.flowzati.archone.catalog.domain.repository.StockLocationRepository;
import com.flowzati.archone.foundation.identity.IdGenerator;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockMove;
import com.flowzati.archone.inventory.movement.domain.entity.StockMoveLine;
import com.flowzati.archone.inventory.movement.domain.aggregate.StockPicking;
import com.flowzati.archone.inventory.balance.domain.aggregate.StockQuant;
import com.flowzati.archone.inventory.balance.domain.service.StockWriteOrder;
import com.flowzati.archone.inventory.movement.domain.repository.StockMoveRepository;
import com.flowzati.archone.inventory.movement.domain.repository.StockPickingRepository;
import com.flowzati.archone.inventory.balance.domain.repository.StockQuantRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * 完成已記錄的 inbound movement，建立 move line 並據此增加實體庫存。
 *
 * <p>這是 application component，不是 transaction owner；呼叫端把 inbound 記錄、完成與
 * availability fact 包在同一個 transaction。Outbound waiting-move allocation 在提交後另開交易。
 */
@Component
public class InboundReceiptCompleter {

  private final StockMoveRepository stockMoveRepository;
  private final StockPickingRepository stockPickingRepository;
  private final StockQuantRepository stockQuantRepository;
  private final StockLocationRepository stockLocationRepository;

  public InboundReceiptCompleter(
      StockMoveRepository stockMoveRepository,
      StockPickingRepository stockPickingRepository,
      StockQuantRepository stockQuantRepository,
      StockLocationRepository stockLocationRepository
  ) {
    this.stockMoveRepository = stockMoveRepository;
    this.stockPickingRepository = stockPickingRepository;
    this.stockQuantRepository = stockQuantRepository;
    this.stockLocationRepository = stockLocationRepository;
  }

  public void complete(List<StockMove> moves, BatchIdentity batchIdentity, Instant now) {
    if (moves == null || moves.isEmpty()) {
      throw new IllegalArgumentException("At least one stock move is required");
    }

    Map<UUID, LocationUsage> locationUsages = loadRequiredLocationUsages(moves);
    moves.forEach(move -> requireIncoming(move, locationUsages));
    List<StockPicking> pickings = loadRequiredPickings(moves);
    Map<ReceivingBatchKey, StockQuant> receivingQuants = resolveReceivingQuants(moves, batchIdentity);

    List<StockMoveLine> lines = completeMovements(moves, receivingQuants, batchIdentity, now);
    completePickings(pickings);
    persistCompletion(receivingQuants.values(), moves, lines, pickings);
  }

  private Map<UUID, LocationUsage> loadRequiredLocationUsages(List<StockMove> moves) {
    Set<UUID> locationIds = new LinkedHashSet<>();
    for (StockMove move : moves) {
      locationIds.add(move.getFromLocationId());
      locationIds.add(move.getToLocationId());
    }

    Map<UUID, LocationUsage> usagesById = new LinkedHashMap<>();
    for (UUID locationId : locationIds) {
      usagesById.put(locationId, usageOf(locationId));
    }
    return usagesById;
  }

  private void requireIncoming(
      StockMove move,
      Map<UUID, LocationUsage> locationUsages
  ) {
    if (locationUsages.get(move.getFromLocationId()) == LocationUsage.INTERNAL) {
      throw new IllegalStateException(
          "Completing an outgoing movement is not implemented until shipping exists");
    }
    if (locationUsages.get(move.getToLocationId()) != LocationUsage.INTERNAL) {
      throw new IllegalStateException(
          "A completed inbound movement must end in an internal location, was "
              + move.getToLocationId());
    }
  }

  private List<StockPicking> loadRequiredPickings(List<StockMove> moves) {
    Set<UUID> pickingIds = moves.stream()
        .map(StockMove::getPickingId)
        .collect(Collectors.toCollection(LinkedHashSet::new));
    if (pickingIds.contains(null)) {
      throw new IllegalStateException("A completed inbound movement must belong to a picking");
    }

    List<StockPicking> pickings = stockPickingRepository.findByIds(pickingIds);
    if (pickings.size() != pickingIds.size()) {
      Set<UUID> found = pickings.stream().map(StockPicking::id).collect(Collectors.toSet());
      Set<UUID> missing = new LinkedHashSet<>(pickingIds);
      missing.removeAll(found);
      throw new IllegalStateException("Stock pickings no longer exist: " + missing);
    }
    return pickings;
  }

  private Map<ReceivingBatchKey, StockQuant> resolveReceivingQuants(
      List<StockMove> moves,
      BatchIdentity batchIdentity
  ) {
    Map<ReceivingBatchKey, StockQuant> quantsByIdentity = new LinkedHashMap<>();
    for (StockMove move : moves) {
      ReceivingBatchKey key = ReceivingBatchKey.from(move, batchIdentity);
      quantsByIdentity.computeIfAbsent(key, this::quantFor);
    }
    return quantsByIdentity;
  }

  private StockQuant quantFor(ReceivingBatchKey key) {
    return stockQuantRepository.findByIdentity(
            key.ownerId(), key.locationId(), key.skuCode(), key.inDate(), key.expiryDate())
        .orElseGet(() -> new StockQuant(
            IdGenerator.nextId(),
            key.ownerId(),
            key.locationId(),
            key.skuCode(),
            key.inDate(),
            key.expiryDate(),
            0,
            0,
            null));
  }

  private List<StockMoveLine> completeMovements(
      List<StockMove> moves,
      Map<ReceivingBatchKey, StockQuant> receivingQuants,
      BatchIdentity batchIdentity,
      Instant now
  ) {
    List<StockMoveLine> lines = new ArrayList<>();
    for (StockMove move : moves) {
      ReceivingBatchKey key = ReceivingBatchKey.from(move, batchIdentity);
      StockQuant quant = receivingQuants.get(key);
      StockMoveLine line = new StockMoveLine(
          IdGenerator.nextId(), move.getId(), quant.getId(), move.getDemandQuantity());
      lines.add(line);

      move.assign(now);
      move.complete(now);
      quant.receive(line);
    }
    return List.copyOf(lines);
  }

  private void completePickings(List<StockPicking> pickings) {
    for (StockPicking picking : pickings) {
      picking.assign();
      picking.complete();
    }
  }

  private void persistCompletion(
      Collection<StockQuant> receivingQuants,
      List<StockMove> moves,
      List<StockMoveLine> lines,
      List<StockPicking> pickings
  ) {
    receivingQuants.stream()
        .sorted(StockWriteOrder.BY_GLOBAL_ORDER)
        .forEach(stockQuantRepository::save);
    stockMoveRepository.saveAll(moves);
    stockMoveRepository.saveLines(lines);
    pickings.forEach(stockPickingRepository::save);
  }

  private LocationUsage usageOf(UUID locationId) {
    return stockLocationRepository.findById(locationId)
        .map(StockLocation::getUsage)
        .orElseThrow(() -> new IllegalStateException(
            "Stock location " + locationId + " no longer exists"));
  }

  /** 入庫日與效期共同參與 StockQuant 的批次身分。 */
  public record BatchIdentity(LocalDate inDate, LocalDate expiryDate) {

    public BatchIdentity {
      if (inDate == null || expiryDate == null) {
        throw new IllegalArgumentException("A batch is identified by its arrival and expiry");
      }
    }
  }

  private record ReceivingBatchKey(
      UUID ownerId,
      UUID locationId,
      String skuCode,
      LocalDate inDate,
      LocalDate expiryDate
  ) {

    private static ReceivingBatchKey from(StockMove move, BatchIdentity batchIdentity) {
      return new ReceivingBatchKey(
          move.getOwnerId(),
          move.getToLocationId(),
          move.getSkuCode(),
          batchIdentity.inDate(),
          batchIdentity.expiryDate());
    }
  }
}
