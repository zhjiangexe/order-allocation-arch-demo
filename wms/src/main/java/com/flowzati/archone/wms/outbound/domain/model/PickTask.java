package com.flowzati.archone.wms.outbound.domain.model;

import java.time.Instant;
import java.util.UUID;

/** 現場實揀任務；不修改庫存，離倉後再由 inventory context 正式完成 StockMove。 */
public class PickTask {

  private final UUID id;
  private final UUID orderLineId;
  private final UUID moveId;
  private final String skuCode;
  private final UUID sourceLocationId;
  private final int requestedQuantity;
  private int pickedQuantity;
  private PickTaskStatus status;
  private Instant confirmedAt;

  public PickTask(
      UUID id,
      UUID orderLineId,
      UUID moveId,
      String skuCode,
      UUID sourceLocationId,
      int requestedQuantity
  ) {
    if (id == null || orderLineId == null || moveId == null || sourceLocationId == null) {
      throw new IllegalArgumentException("Pick task requires task, order line, move and location IDs");
    }
    if (skuCode == null || skuCode.isBlank()) {
      throw new IllegalArgumentException("Pick task SKU is required");
    }
    if (requestedQuantity <= 0) {
      throw new IllegalArgumentException("Requested pick quantity must be positive");
    }
    this.id = id;
    this.orderLineId = orderLineId;
    this.moveId = moveId;
    this.skuCode = skuCode;
    this.sourceLocationId = sourceLocationId;
    this.requestedQuantity = requestedQuantity;
    this.status = PickTaskStatus.PENDING;
  }

  /** 一次回報實揀量；主流程先不做拆次、多人與容器演算法。 */
  public PickTaskStatus confirm(int actualQuantity, Instant confirmedAt) {
    if (status == PickTaskStatus.PICKED || status == PickTaskStatus.SHORT_PICKED) {
      return status;
    }
    if (status != PickTaskStatus.PENDING) {
      throw new IllegalStateException("Only a pending pick task can be confirmed, was " + status);
    }
    if (actualQuantity < 0 || actualQuantity > requestedQuantity) {
      throw new IllegalArgumentException("Actual quantity must be between zero and requested quantity");
    }
    if (confirmedAt == null) {
      throw new IllegalArgumentException("Pick confirmation time is required");
    }
    this.pickedQuantity = actualQuantity;
    this.confirmedAt = confirmedAt;
    this.status = actualQuantity == requestedQuantity
        ? PickTaskStatus.PICKED
        : PickTaskStatus.SHORT_PICKED;
    return status;
  }

  public boolean cancel() {
    if (status == PickTaskStatus.CANCELLED) {
      return false;
    }
    if (status != PickTaskStatus.PENDING) {
      throw new IllegalStateException("A started pick task requires physical putback");
    }
    status = PickTaskStatus.CANCELLED;
    return true;
  }

  public UUID id() {
    return id;
  }

  public UUID orderLineId() {
    return orderLineId;
  }

  public UUID moveId() {
    return moveId;
  }

  public String skuCode() {
    return skuCode;
  }

  public UUID sourceLocationId() {
    return sourceLocationId;
  }

  public int requestedQuantity() {
    return requestedQuantity;
  }

  public int pickedQuantity() {
    return pickedQuantity;
  }

  public PickTaskStatus status() {
    return status;
  }

  public Instant confirmedAt() {
    return confirmedAt;
  }
}
