package com.flowzati.archone.logisticsdata.domain.aggregate;

import com.flowzati.archone.logisticsdata.domain.type.TemperatureZone;
import java.util.UUID;

/**
 * 商品的「款」。一款有多個規格（{@link Sku}），溫層屬款層級。
 *
 * <p>以代理鍵識別，而 {@code (ownerId, productCode)} 是它的自然鍵：在 3PL 裡款號由貨主
 * 自訂、跨貨主必然撞號，因此單獨的 {@code productCode} 不指向任何東西——這件事由資料庫的
 * unique constraint 保證，不需要讓它當主鍵。
 *
 * <p>溫層放在這一層而非 {@link Sku}，是為了讓「同款兩種溫層」在結構上無法產生——那不是
 * 無效值而是無效組合，收單時不會報錯，會拖到 R6 依節點 capabilities 篩選時才浮現。
 */
public class Product {

    private final UUID id;
    private final UUID ownerId;
    private final String productCode;
    private final String name;
    private final TemperatureZone temperatureZone;

    public Product(UUID id, UUID ownerId, String productCode, String name, TemperatureZone temperatureZone) {
        if (id == null) {
            throw new IllegalArgumentException("Product ID is required");
        }
        if (ownerId == null) {
            throw new IllegalArgumentException("Owner ID is required");
        }
        if (productCode == null || productCode.isBlank()) {
            throw new IllegalArgumentException("Product code is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Product name is required");
        }
        if (temperatureZone == null) {
            throw new IllegalArgumentException("Temperature zone is required");
        }
        this.id = id;
        this.ownerId = ownerId;
        this.productCode = productCode;
        this.name = name;
        this.temperatureZone = temperatureZone;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwnerId() {
        return ownerId;
    }

    public String getProductCode() {
        return productCode;
    }

    public String getName() {
        return name;
    }

    public TemperatureZone getTemperatureZone() {
        return temperatureZone;
    }
}
