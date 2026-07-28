package com.flowzati.archone.catalog.infrastructure.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * 貨主與倉庫的指派。只有兩個外鍵欄位，沒有屬性。
 *
 * <p>用 {@link EmbeddedId} 而不是 {@code @IdClass}：後者要求把主鍵欄位在實體與鍵類別上
 * 各寫一次，兩邊漂掉時症狀難懂。Embeddable **就是**那把鍵，沒有重複可漂。
 *
 * <p>這裡用複合鍵而非代理鍵，與 {@code products}／{@code skus} 用代理鍵的判準一致而非相反：
 * 那兩者是有身分的實體，這一張是純粹的關係。
 */
@Entity
@Table(name = "owner_nodes")
public class OwnerNodeEntity {

  @EmbeddedId
  private OwnerNodeId id;

  protected OwnerNodeEntity() {
  }

  public OwnerNodeEntity(UUID ownerId, UUID nodeId) {
    this.id = new OwnerNodeId(ownerId, nodeId);
  }

  public OwnerNodeId getId() {
    return id;
  }

  @Embeddable
  public static class OwnerNodeId implements Serializable {

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "node_id", nullable = false)
    private UUID nodeId;

    protected OwnerNodeId() {
    }

    public OwnerNodeId(UUID ownerId, UUID nodeId) {
      this.ownerId = ownerId;
      this.nodeId = nodeId;
    }

    public UUID getOwnerId() {
      return ownerId;
    }

    public UUID getNodeId() {
      return nodeId;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other) {
        return true;
      }
      if (!(other instanceof OwnerNodeId that)) {
        return false;
      }
      return Objects.equals(ownerId, that.ownerId) && Objects.equals(nodeId, that.nodeId);
    }

    @Override
    public int hashCode() {
      return Objects.hash(ownerId, nodeId);
    }
  }
}
