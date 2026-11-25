package com.flowzati.archone.logisticsdata.infrastructure.persistence.jpa.model;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/**
 * 貨主與倉庫的指派。只有兩個外鍵欄位，沒有屬性。
 *
 * <p>用 {@link EmbeddedId} 而不是 {@code @IdClass}：後者要求把主鍵欄位在實體與鍵類別上
 * 各寫一次，兩邊漂掉時症狀難懂。{@link OwnerFacilityPrimaryKey} **就是**那把鍵，沒有重複可漂。
 *
 * <p>這裡用複合鍵而非代理鍵，與 {@code products}／{@code skus} 用代理鍵的判準一致而非相反：
 * 那兩者是有身分的實體，這一張是純粹的關係。
 */
@Entity
@Table(name = "owner_facilities")
public class OwnerFacilityEntity {

    @EmbeddedId
    private OwnerFacilityPrimaryKey id;

    protected OwnerFacilityEntity() {}

    public OwnerFacilityEntity(UUID ownerId, UUID facilityId) {
        this.id = new OwnerFacilityPrimaryKey(ownerId, facilityId);
    }

    public OwnerFacilityPrimaryKey getId() {
        return id;
    }
}
