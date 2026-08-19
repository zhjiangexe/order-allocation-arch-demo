package com.flowzati.archone.inventory.allocation.infrastructure.source.order;

import com.flowzati.archone.catalog.domain.type.PickingDirection;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand;
import com.flowzati.archone.inventory.allocation.application.command.AcceptAllocationDemandCommand.SourceDemandLine;
import com.flowzati.archone.inventory.allocation.application.command.AllocationExecutionIntent;
import com.flowzati.archone.inventory.allocation.application.source.order.OrderAllocationDemandSource;
import com.flowzati.archone.inventory.allocation.domain.valueobject.SourceAllocationUnit;
import com.flowzati.archone.inventory.allocation.infrastructure.entity.OrderAllocationSourceLineEntity;
import com.flowzati.archone.inventory.allocation.infrastructure.repository.jpa.JpaOrderAllocationSourceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * 訂單來源轉接器：把 order 發布的 allocation read model 正規化成共用 acceptance contract。
 *
 * <p>Adapter 負責 source-specific identity 與欄位映射；是否符合 FIFO、庫存是否足夠，留給共用
 * allocation core 決定。
 */
@Component
public class OrderAllocationDemandAdapter implements OrderAllocationDemandSource {

  private final JpaOrderAllocationSourceRepository repository;

  public OrderAllocationDemandAdapter(JpaOrderAllocationSourceRepository repository) {
    this.repository = repository;
  }

  @Override
  public Optional<AcceptAllocationDemandCommand> find(UUID sourceId) {
    // Repository 已依 stable sourceLineId 排序；後續 domain 仍會自行 canonicalize，避免依賴傳輸順序。
    List<OrderAllocationSourceLineEntity> rows = repository.findBySourceIdOrderBySourceLineIdAsc(sourceId);
    if (rows.isEmpty()) {
      // Order 已取消或 read model 尚不存在時，AllocateOrderUsecase 直接安全結束。
      return Optional.empty();
    }
    OrderAllocationSourceLineEntity first = rows.getFirst();
    if (first.getPickingTypeId() == null
        || first.getSourceLocationId() == null
        || first.getDestinationLocationId() == null) {
      throw new IllegalStateException("Facility for order " + sourceId + " has no outbound operation type");
    }
    if (rows.stream().anyMatch(row ->
        !first.getOwnerId().equals(row.getOwnerId())
            || !first.getFacilityId().equals(row.getFacilityId())
            || !first.getSourceLocationId().equals(row.getSourceLocationId()))) {
      throw new IllegalStateException("One source allocation unit spans multiple inventory scopes");
    }

    // PRIMARY 表示 v1 一張訂單只有一個可獨立配置單元；未來若要拆單，必須由新的 source spec 定義 key。
    List<SourceDemandLine> list = rows.stream()
        .map(row -> new SourceDemandLine(
            row.getSourceLineId(), row.getSkuCode(), row.getQuantity(),
            row.getSourceLineReferenceId()))
        .toList();
    AllocationExecutionIntent executionIntent = new AllocationExecutionIntent(
        first.getPickingTypeId(),
        PickingDirection.OUTBOUND,
        first.getSourceLocationId(),
        first.getDestinationLocationId(),
        true,
        sourceId);
    return Optional.of(new AcceptAllocationDemandCommand(
        SourceAllocationUnit.primaryOrder(sourceId.toString()),
        first.getOwnerId(),
        first.getFacilityId(),
        first.getSourceLocationId(),
        first.getRequiredBy(),
        first.getReleasePriority(),
        first.getEnqueuedAt(),
        list,
        executionIntent));
  }
}
