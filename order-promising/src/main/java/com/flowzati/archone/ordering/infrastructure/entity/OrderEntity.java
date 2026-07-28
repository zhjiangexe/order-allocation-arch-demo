package com.flowzati.archone.ordering.infrastructure.entity;

import com.flowzati.archone.ordering.domain.model.OrderStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "orders",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_orders_owner_external_no",
        columnNames = {"owner_id", "external_order_no"}
    ),
    indexes = @Index(name = "idx_orders_recent", columnList = "placed_at,id")
)
public class OrderEntity {

  @Id
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "external_order_no", nullable = false)
  private String externalOrderNo;

  @Column(name = "ship_to_zone", nullable = false)
  private String shipToZone;

  @Column(name = "ship_to_address", nullable = false)
  private String shipToAddress;

  @Column(name = "promised_delivery_date", nullable = false)
  private LocalDate promisedDeliveryDate;

  @Column(name = "fulfillment_node_id", nullable = false)
  private UUID fulfillmentNodeId;

  /**
   * 訂單與其行是同一個 aggregate：一起讀、一起寫、一起失效。以 cascade 表達這件事，讓
   * 「寫訂單時行也要寫進去」由 JPA 保證，而不是靠 repository 每次記得呼叫第二個
   * repository——後者漏掉時不會編譯失敗，只會少寫幾行資料。
   *
   * <p>{@code @OrderBy} 讓載入順序穩定：沒有它時列的順序由資料庫決定，往返之後行的次序
   * 可能與寫入時不同，而畫面與斷言都會因此不穩。
   */
  @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
  @JoinColumn(name = "order_id", nullable = false)
  @OrderBy("lineNo ASC")
  private List<OrderLineEntity> lines;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  private OrderStatus status;

  @Column(name = "placed_at", nullable = false)
  private Instant placedAt;

  @Column(name = "allocated_at")
  private Instant allocatedAt;

  @Column(name = "backordered_since")
  private Instant backorderedSince;

  @Column(name = "cancelled_at")
  private Instant cancelledAt;

  @Version
  @Column(nullable = false)
  private Long version;

  protected OrderEntity() {
  }

  public OrderEntity(
      UUID id,
      UUID ownerId,
      String externalOrderNo,
      String shipToZone,
      String shipToAddress,
      LocalDate promisedDeliveryDate,
      UUID fulfillmentNodeId,
      List<OrderLineEntity> lines,
      OrderStatus status,
      Instant placedAt,
      Instant allocatedAt,
      Instant backorderedSince,
      Instant cancelledAt,
      Long version
  ) {
    this.id = id;
    this.ownerId = ownerId;
    this.externalOrderNo = externalOrderNo;
    this.shipToZone = shipToZone;
    this.shipToAddress = shipToAddress;
    this.promisedDeliveryDate = promisedDeliveryDate;
    this.fulfillmentNodeId = fulfillmentNodeId;
    this.lines = lines;
    this.status = status;
    this.placedAt = placedAt;
    this.allocatedAt = allocatedAt;
    this.backorderedSince = backorderedSince;
    this.cancelledAt = cancelledAt;
    this.version = version;
  }

  public UUID getId() {
    return id;
  }

  public UUID getOwnerId() {
    return ownerId;
  }

  public String getExternalOrderNo() {
    return externalOrderNo;
  }

  public String getShipToZone() {
    return shipToZone;
  }

  public String getShipToAddress() {
    return shipToAddress;
  }

  public LocalDate getPromisedDeliveryDate() {
    return promisedDeliveryDate;
  }

  public UUID getFulfillmentNodeId() {
    return fulfillmentNodeId;
  }

  public List<OrderLineEntity> getLines() {
    return lines;
  }

  public OrderStatus getStatus() {
    return status;
  }

  public Instant getPlacedAt() {
    return placedAt;
  }

  public Instant getAllocatedAt() {
    return allocatedAt;
  }

  public Instant getBackorderedSince() {
    return backorderedSince;
  }

  public Instant getCancelledAt() {
    return cancelledAt;
  }

  public Long getVersion() {
    return version;
  }
}
