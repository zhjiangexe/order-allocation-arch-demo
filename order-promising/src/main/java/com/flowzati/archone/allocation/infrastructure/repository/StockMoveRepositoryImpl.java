package com.flowzati.archone.allocation.infrastructure.repository;

import com.flowzati.archone.allocation.domain.model.StockMove;
import com.flowzati.archone.allocation.domain.model.StockMoveLine;
import com.flowzati.archone.allocation.domain.repository.StockMoveRepository;
import com.flowzati.archone.allocation.infrastructure.mapper.StockMoveMapper;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockMoveLineRepository;
import com.flowzati.archone.allocation.infrastructure.repository.jpa.JpaStockMoveRepository;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Repository;

@Repository
public class StockMoveRepositoryImpl implements StockMoveRepository {

  private final JpaStockMoveRepository moveRepository;
  private final JpaStockMoveLineRepository moveLineRepository;

  public StockMoveRepositoryImpl(
      JpaStockMoveRepository moveRepository,
      JpaStockMoveLineRepository moveLineRepository
  ) {
    this.moveRepository = moveRepository;
    this.moveLineRepository = moveLineRepository;
  }

  @Override
  public void save(StockMove move) {
    moveRepository.save(StockMoveMapper.toEntity(move));
  }

  @Override
  public void saveAll(Collection<StockMove> moves) {
    // 以 id 排序寫入，與庫存列的 WRITE_ORDER 同一個判準：同一批交易若以不同順序碰同一組列，
    // 併發下就有死鎖的機會。搬運的爭用遠低於庫存，但一致的順序不花任何成本。
    moves.stream()
        .sorted(Comparator.comparing(StockMove::getId))
        .map(StockMoveMapper::toEntity)
        .forEach(moveRepository::save);
  }

  @Override
  public void saveLines(Collection<StockMoveLine> lines) {
    lines.stream()
        .sorted(Comparator.comparing(StockMoveLine::id))
        .map(StockMoveMapper::toEntity)
        .forEach(moveLineRepository::save);
  }

  @Override
  public List<StockMove> findWaitingInFifoOrder(
      UUID ownerId, UUID locationId, String skuCode, int limit) {
    List<UUID> pickingIds = moveRepository.findWaitingPickingIdsInFifoOrder(
        ownerId, locationId, skuCode, Limit.of(limit));
    return findByPickingIds(pickingIds);
  }

  @Override
  public List<StockMove> findByPickingIds(Collection<UUID> pickingIds) {
    if (pickingIds.isEmpty()) {
      return List.of();
    }
    return moveRepository.findByPickingIdInOrderByOrderLineIdAsc(pickingIds).stream()
        .map(StockMoveMapper::toDomain)
        .toList();
  }

  @Override
  public List<StockMove> findByOrderLineIds(Collection<UUID> orderLineIds) {
    if (orderLineIds.isEmpty()) {
      return List.of();
    }
    return moveRepository.findByOrderLineIdIn(orderLineIds).stream()
        .map(StockMoveMapper::toDomain)
        .toList();
  }

  @Override
  public List<StockMoveLine> findLinesOf(Collection<UUID> moveIds) {
    if (moveIds.isEmpty()) {
      return List.of();
    }
    return moveLineRepository.findByMoveIdIn(moveIds).stream()
        .map(StockMoveMapper::toDomain)
        .toList();
  }

  @Override
  public void deleteLinesOf(Collection<UUID> moveIds) {
    if (moveIds.isEmpty()) {
      return;
    }
    moveLineRepository.deleteByMoveIdIn(moveIds);
  }
}
